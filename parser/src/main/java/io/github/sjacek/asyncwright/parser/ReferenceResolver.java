package io.github.sjacek.asyncwright.parser;

import io.github.sjacek.asyncwright.api.AsyncApi30;
import io.github.sjacek.asyncwright.api.BuiltinRuleId;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sjacek.asyncwright.api.Diagnostic;
import io.github.sjacek.asyncwright.api.Severity;
import io.github.sjacek.asyncwright.api.SourceLocation;
import io.github.sjacek.asyncwright.api.AsyncApiReferences;
import io.github.sjacek.asyncwright.api.ResolvedReference;

import java.io.IOException;
import java.net.URI;
import java.util.*;

/** Resolves document positions without replacing references in the source or model. */
public final class ReferenceResolver implements AsyncApiReferences {
    private static final ObjectMapper MAPPER = ModelMapping.mapper();
    private final SourceDocument source;
    private final DocumentIndex index;
    private final Map<String, String> targets = new LinkedHashMap<>();
    private final Set<String> invalid = new HashSet<>();

    ReferenceResolver(SourceDocument source, DocumentIndex index) {
        this.source = source;
        this.index = index;
    }

    List<Diagnostic> validate() {
        List<Diagnostic> errors = new ArrayList<>();
        for (var use : index.references) {
            String ref = source.root.at(use.pointer()).path(REF).asText();
            try {
                String target = pointerOf(ref);
                if (source.root.at(target).isMissingNode()) {
                    fail(errors, use.pointer(), BuiltinRuleId.REFERENCE_MISSING, "Reference target does not exist: " + ref);
                } else if (use.kind() != DocumentIndex.Kind.OTHER && index.kinds.get(target) != use.kind()) {
                    fail(errors, use.pointer(), BuiltinRuleId.REFERENCE_TYPE, "Reference must target a "
                            + use.kind().name().toLowerCase(Locale.ROOT) + " object");
                } else targets.put(use.pointer(), target);
            } catch (IllegalArgumentException exception) {
                fail(errors, use.pointer(), ref.startsWith("#") || ref.isEmpty()
                        ? BuiltinRuleId.REFERENCE_POINTER : BuiltinRuleId.UNSUPPORTED_REFERENCE, exception.getMessage());
            }
        }
        Map<String, Integer> states = new HashMap<>();
        for (String pointer : index.kinds.keySet()) visit(pointer, states, new ArrayList<>(), errors);
        return errors;
    }

    private void visit(String pointer, Map<String, Integer> states, List<String> stack, List<Diagnostic> errors) {
        if (states.getOrDefault(pointer, 0) != 0) return;
        states.put(pointer, 1);
        stack.add(pointer);
        List<String> edges = new ArrayList<>(index.children.getOrDefault(pointer, List.of()));
        if (targets.containsKey(pointer)) edges.add(targets.get(pointer));
        for (String target : edges) {
            if (states.getOrDefault(target, 0) == 1) {
                List<String> cycle = stack.subList(stack.indexOf(target), stack.size());
                // Concrete recursive schemas are useful; a ring consisting only of aliases has no value to resolve.
                boolean recursiveSchema = cycle.stream().allMatch(p -> index.kinds.get(p) == DocumentIndex.Kind.SCHEMA)
                        && cycle.stream().anyMatch(p -> !source.root.at(p).has(REF));
                if (!recursiveSchema) {
                    for (String member : cycle) if (source.root.at(member).has(REF)) invalid.add(member);
                    String location = source.root.at(pointer).has(REF) ? pointer : target;
                    errors.add(Diagnostic.at(BuiltinRuleId.REFERENCE_CYCLE, Severity.ERROR,
                            "Unresolvable reference cycle outside a recursive schema",
                            source.location(location + "/" + REF), cycle.stream().map(source::location).toList()));
                }
            } else visit(target, states, stack, errors);
        }
        stack.remove(stack.size() - 1);
        states.put(pointer, 2);
    }

    private void fail(List<Diagnostic> errors, String pointer, BuiltinRuleId rule, String message) {
        invalid.add(pointer);
        errors.add(Diagnostic.at(rule, Severity.ERROR, message, source.location(pointer + "/" + REF), List.of()));
    }

    static String pointerOf(String reference) {
        if (!(reference.startsWith("#") || reference.isEmpty()))
            throw new IllegalArgumentException("External references are not supported in 0.1");
        String pointer;
        // URI fragment decoding preserves literal plus signs, unlike form URL decoding.
        try { pointer = reference.isEmpty() ? "" : URI.create(reference).getFragment(); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Malformed reference URI"); }
        if (pointer == null || (!pointer.isEmpty() && !pointer.startsWith("/"))
                || java.util.regex.Pattern.compile("~(?:[^01]|$)").matcher(pointer).find())
            throw new IllegalArgumentException("Expected a local JSON Pointer with valid ~0/~1 escapes");
        return pointer;
    }

    /** The first validated hop, before following aliases, for rules about reference locations. */
    Optional<String> directTarget(String pointer) {
        return invalid.contains(pointer) ? Optional.empty() : Optional.ofNullable(targets.get(pointer));
    }

    /** Accepts the pointer of a Reference Object (not the literal contents of $ref). */
    public Optional<ResolvedReference> resolve(String pointer) {
        Set<String> visited = new HashSet<>();
        String current = pointer;
        while (targets.containsKey(current)) {
            if (invalid.contains(current) || !visited.add(current)) return Optional.empty();
            current = targets.get(current);
        }
        JsonNode value = source.root.at(current);
        if (invalid.contains(current) || value.isMissingNode() || value.has(REF)) return Optional.empty();
        return Optional.of(new ResolvedReference(current, value));
    }

    public <T> Optional<T> resolve(String pointer, Class<T> type) throws IOException {
        Optional<ResolvedReference> target = resolve(pointer);
        return target.isPresent() ? Optional.of(MAPPER.treeToValue(
                mappingTree(target.get().pointer(), target.get().node()), type)) : Optional.empty();
    }

    JsonNode mappingTree(String base, JsonNode tree) {
        // Adapt only a copy for the generated model: the resolver and diagnostics must retain original source.
        JsonNode mapping = tree.deepCopy();
        for (var entry : index.kinds.entrySet()) {
            if (entry.getValue() != DocumentIndex.Kind.SCHEMA
                    || !(entry.getKey().equals(base) || entry.getKey().startsWith(base + "/"))) continue;
            JsonNode value = mapping.at(entry.getKey().substring(base.length()));
            if (value instanceof com.fasterxml.jackson.databind.node.ObjectNode object
                    && object.has(SCHEMA) && !object.has(SCHEMA_FORMAT) && !object.has(REF))
                object.put(SCHEMA_FORMAT, AsyncApi30.SchemaFormat.JSON.mediaType());
        }
        for (var use : index.references) {
            if (!(use.pointer().equals(base) || use.pointer().startsWith(base + "/"))) continue;
            JsonNode value = mapping.at(use.pointer().substring(base.length()));
            if (value instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                JsonNode ref = object.get(REF);
                object.removeAll();
                object.set(REF, ref);
            }
        }
        return mapping;
    }

    public List<SourceLocation> related(String pointer) {
        Set<SourceLocation> locations = new LinkedHashSet<>();
        for (var use : index.references) {
            try {
                String target = pointerOf(source.root.at(use.pointer()).path(REF).asText());
                Set<String> visited = new HashSet<>();
                while (visited.add(target)) {
                    if (pointer.equals(target) || pointer.startsWith(target + "/")) {
                        locations.add(source.location(use.pointer() + "/" + REF));
                        break;
                    }
                    JsonNode value = source.root.at(target);
                    if (!value.has(REF)) break;
                    target = pointerOf(value.path(REF).asText());
                }
            } catch (IllegalArgumentException ignored) { /* Reported in reference validation. */ }
        }
        return List.copyOf(locations);
    }
}
