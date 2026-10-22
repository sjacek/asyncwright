package io.github.sjacek.asyncwright.f0;

import com.asyncapi.bindings.amqp.AMQPChannelBinding;
import com.asyncapi.schemas.asyncapi.AsyncAPISchema;
import com.asyncapi.schemas.asyncapi.Reference;
import com.asyncapi.v3._0_0.model.AsyncAPI;
import com.asyncapi.v3._0_0.model.channel.Channel;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Reproducible F0 experiment, deliberately separate from the future production parser. */
public final class F0Probe {
    private static final String REF = "$ref";
    private static final String MAPPED = "mapped";
    private static final String LOSSLESS = "lossless";
    private static final String INTERNAL_EXCEPTIONS = "internalExceptions";
    private static final String AMQP_BINDING = "amqp";
    private static final ObjectMapper JSON = JsonMapper.builder()
            .defaultPropertyInclusion(JsonInclude.Value.construct(
                    JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL)).build();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final StringBuilder UPSTREAM_STDERR = new StringBuilder();
    private static final List<String> CONTRACTS = List.of(
            "document-import-job-events.yaml", "forum-live-events.yaml",
            "office-invalidation-events.yaml", "ops-application-events.yaml",
            "portal-notification-live-events.yaml");

    private F0Probe() {}

    public static void main(String[] args) throws Exception {
        Path contracts = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        Map<String, Object> report = new TreeMap<>();
        report.put("asyncapiCore", "1.0.0-RC4");
        report.put("jackson", JSON.version().toString());
        report.put("java", System.getProperty("java.version"));
        report.put("scope", "F0 only: mapping is not specification validation; resolver is an experiment");
        List<Map<String, Object>> results = new ArrayList<>();
        int rawPass = 0;
        int rawMapped = 0;
        int rawClean = 0;
        int resolvedPass = 0;
        int bindingsPass = 0;
        int referenceCount = 0;
        for (String name : CONTRACTS) {
            byte[] source = Files.readAllBytes(contracts.resolve(name));
            JsonNode original = YAML.readTree(source);
            JsonNode resolved = resolve(original, original, new LinkedHashSet<>());
            Map<String, Object> result = new TreeMap<>();
            result.put("file", name);
            result.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)));
            int refs = countRefs(original);
            referenceCount += refs;
            result.put("localReferences", refs);
            Map<String, Object> raw = mapDocument(original);
            Map<String, Object> expanded = mapDocument(resolved);
            result.put("raw", raw);
            result.put("resolved", expanded);
            if (Boolean.TRUE.equals(raw.get(MAPPED))) rawMapped++;
            if (Boolean.TRUE.equals(raw.get(MAPPED)) && ((List<?>) raw.get(INTERNAL_EXCEPTIONS)).isEmpty()) rawClean++;
            if (Boolean.TRUE.equals(raw.get(LOSSLESS))) rawPass++;
            if (Boolean.TRUE.equals(expanded.get(LOSSLESS))) resolvedPass++;

            // Isolate channel bindings so a failure in schemas/operations cannot hide their result.
            List<Map<String, Object>> bindings = new ArrayList<>();
            for (var entry : original.path("channels").properties()) {
                JsonNode binding = entry.getValue().path("bindings").path(AMQP_BINDING);
                if (binding.isMissingNode()) continue;
                Map<String, Object> item = new TreeMap<>();
                item.put("channel", entry.getKey());
                try {
                    Channel channel = Objects.requireNonNull(JSON.treeToValue(entry.getValue(), Channel.class),
                            "Channel mapping returned null");
                    Object typed = requiredEntry(channel.getBindings(), AMQP_BINDING);
                    item.put("type", typed.getClass().getName());
                    item.put("typed", typed instanceof AMQPChannelBinding);
                    List<String> differences = differences(binding, JSON.valueToTree(typed));
                    item.put("differences", differences);
                    item.put(LOSSLESS, differences.isEmpty());
                } catch (Exception exception) {
                    item.put("error", error(exception));
                    item.put("typed", false);
                    item.put(LOSSLESS, false);
                }
                bindings.add(item);
            }
            boolean allBindings = !bindings.isEmpty() && bindings.stream().allMatch(b -> Boolean.TRUE.equals(b.get("typed")));
            if (allBindings) bindingsPass++;
            result.put("amqpBindings", bindings);

            // Diagnostic-only experiment: removing nullable is lossy, never counts as P1 success.
            JsonNode withoutNullable = original.deepCopy();
            List<String> removed = new ArrayList<>();
            removeNullable(withoutNullable, "", removed);
            result.put("removedNullablePaths", removed);
            result.put("diagnosticWithoutNullable", mapDocument(withoutNullable));
            result.put("diagnosticResolvedWithoutNullable",
                    mapDocument(resolve(withoutNullable, withoutNullable, new LinkedHashSet<>())));

            // None of the real inputs currently has x- fields: probe representative locations explicitly.
            ObjectNode extended = withoutNullable.deepCopy();
            injectExtension(extended);
            for (String pointer : List.of("/info", "/channels", "/operations", "/components/messages", "/components/schemas")) {
                JsonNode node = extended.at(pointer);
                if (pointer.equals("/info")) injectExtension(node);
                else if (node.isObject() && node.elements().hasNext()) injectExtension(node.elements().next());
            }
            JsonNode firstChannel = extended.path("channels").elements().next();
            injectExtension(firstChannel.path("bindings").path(AMQP_BINDING));
            result.put("diagnosticExtensionsWithoutNullable", mapDocument(extended));
            results.add(result);
            System.out.printf("%s: raw=%s, resolved=%s, typed AMQP=%s, refs=%d%n", name,
                    raw.get(LOSSLESS), expanded.get(LOSSLESS), allBindings, refs);
        }
        Map<String, Object> controls = controls();
        boolean cycleReference = Boolean.TRUE.equals(controls.get("cyclePropertyIsReference"));
        report.put("contracts", results);
        report.put("controls", controls);
        report.put("localReferences", referenceCount);
        report.put("rawLosslessContracts", rawPass);
        report.put("rawMappedContracts", rawMapped);
        report.put("rawMappedWithoutInternalExceptions", rawClean);
        report.put("resolvedLosslessContracts", resolvedPass);
        report.put("typedAmqpContracts", bindingsPass);
        boolean p1 = rawClean == 5 && rawPass == 5 && resolvedPass == 5 && bindingsPass == 5 && cycleReference;
        report.put("p1Passed", p1);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("result.json").toFile(), report);
        Files.writeString(output.resolve("upstream-stderr.log"), UPSTREAM_STDERR, StandardCharsets.UTF_8);
        System.out.println("P1 passed: " + p1 + "; report: " + output.resolve("result.json"));
        if (!p1) System.exit(1);
    }

    private static Map<String, Object> mapDocument(JsonNode input) {
        Map<String, Object> result = new TreeMap<>();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (PrintStream redirect = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setErr(redirect);
            AsyncAPI document = JSON.treeToValue(input, AsyncAPI.class);
            result.put(MAPPED, true);
            JsonNode serialized = JSON.valueToTree(document);
            List<String> differences = differences(input, serialized);
            result.put("differences", differences);
            result.put(LOSSLESS, differences.isEmpty());
            result.put("losslessIgnoringAmqpVhostDefault", differences.stream().allMatch(
                    difference -> difference.contains("/bindings/amqp/exchange/vhost: added \"/\"")));
        } catch (Exception exception) {
            result.putIfAbsent(MAPPED, false);
            result.put(LOSSLESS, false);
            result.put("error", error(exception));
        } finally {
            System.setErr(previous);
            String stderr = captured.toString(StandardCharsets.UTF_8);
            result.put(INTERNAL_EXCEPTIONS, stderr.lines().filter(line -> line.startsWith("com.fasterxml.")
                    && line.contains("Exception:")).toList());
            UPSTREAM_STDERR.append(stderr);
        }
        return result;
    }

    private static Map<String, Object> controls() throws Exception {
        Map<String, Object> result = new TreeMap<>();
        // Minimal, valid recursive schema: the criterion is the actual Java value at the cycle edge.
        JsonNode recursive = JSON.readTree("""
                {"asyncapi":"3.0.0","info":{"title":"Cycle probe","version":"1"},
                 "components":{"schemas":{"Node":{"type":"object","properties":{
                   "child":{REF:"#/components/schemas/Node"}}}}}}
                """);
        JsonNode expanded = resolve(recursive, recursive, new LinkedHashSet<>());
        result.put("recursiveDocument", mapDocument(expanded));
        try {
            AsyncAPI mapped = Objects.requireNonNull(JSON.treeToValue(expanded, AsyncAPI.class),
                    "Recursive document mapping returned null");
            var components = Objects.requireNonNull(mapped.getComponents(), "Mapped components are missing");
            AsyncAPISchema schema = (AsyncAPISchema) requiredEntry(components.getSchemas(), "Node");
            AsyncAPISchema child = (AsyncAPISchema) requiredEntry(schema.getProperties(), "child");
            // The resolver expands one level at the first use and leaves the repeated reference.
            Object cycle = requiredEntry(child.getProperties(), "child");
            result.put("cyclePropertyType", cycle.getClass().getName());
            result.put("cyclePropertyIsReference", cycle instanceof Reference);
            result.put("cyclePropertyValue", JSON.valueToTree(cycle));
        } catch (Exception exception) {
            result.put("cyclePropertyIsReference", false);
            result.put("cycleError", error(exception));
        }
        JsonNode channelDocument = JSON.readTree("""
                {"asyncapi":"3.0.0","info":{"title":"Reference probe","version":"1"},
                 "channels":{"c":{"address":"a"}},
                 "operations":{"send":{"action":"send","channel":{REF:"#/channels/c"}}}}
                """);
        result.put("operationReferenceRaw", mapDocument(channelDocument));
        result.put("operationReferenceResolved", mapDocument(resolve(channelDocument, channelDocument, new LinkedHashSet<>())));

        // Negative controls show whether this is a validator or merely a model mapper.
        result.put("missingRequiredInfo", mapDocument(JSON.readTree("{\"asyncapi\":\"3.0.0\"}")));
        result.put("danglingReference", mapDocument(JSON.readTree("""
                {"asyncapi":"3.0.0","info":{"title":"Invalid","version":"1"},
                 "operations":{"send":{"action":"send","channel":{REF:"#/channels/missing"}}}}
                """)));
        JsonNode escaped = JSON.readTree("{\"a/b~c\":{\"value\":7},\"use\":{\"$ref\":\"#/a~1b~0c\"}}");
        if (resolve(escaped, escaped, new LinkedHashSet<>()).at("/use/value").intValue() != 7)
            throw new AssertionError("F0 resolver does not decode JSON Pointer escapes");
        assertResolverRejects("{\"use\":{\"$ref\":\"#/missing\"}}");
        assertResolverRejects("{\"a\":{\"$ref\":\"#/b\"},\"b\":{\"$ref\":\"#/a\"}}");
        result.put("experimentalResolverControlsPassed", true);
        return result;
    }

    private static void assertResolverRejects(String source) throws Exception {
        JsonNode root = JSON.readTree(source);
        try {
            resolve(root, root, new LinkedHashSet<>());
            throw new AssertionError("Resolver accepted missing/cyclic reference");
        } catch (IllegalArgumentException expected) {
            // Missing targets and pure reference loops must not be silently treated as schemas.
        }
    }

    /** Only a spike dereferencer for this corpus; not the production, context-aware P4 resolver. */
    private static JsonNode resolve(JsonNode node, JsonNode root, Set<String> active) {
        if (node.isObject() && node.has(REF)) {
            String ref = node.path(REF).asText();
            if (!ref.startsWith("#/")) throw new IllegalArgumentException("Non-local reference: " + ref);
            JsonNode target = root.at(ref.substring(1));
            if (target.isMissingNode()) throw new IllegalArgumentException("Missing reference: " + ref);
            if (active.contains(ref)) {
                if (target.has(REF)) throw new IllegalArgumentException("Pure reference loop: " + ref);
                return node.deepCopy();
            }
            Set<String> next = new LinkedHashSet<>(active);
            next.add(ref);
            return resolve(target, root, next);
        }
        if (node.isObject()) {
            ObjectNode copy = JSON.createObjectNode();
            node.properties().forEach(e -> copy.set(e.getKey(), resolve(e.getValue(), root, active)));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JSON.createArrayNode();
            node.forEach(value -> copy.add(resolve(value, root, active)));
            return copy;
        }
        return node.deepCopy();
    }

    private static int countRefs(JsonNode node) {
        int count = node.isObject() && node.has(REF) ? 1 : 0;
        for (JsonNode child : node) count += countRefs(child);
        return count;
    }

    private static void removeNullable(JsonNode node, String pointer, List<String> removed) {
        if (node.isObject()) {
            if (node.has("nullable")) {
                removed.add(pointer + "/nullable");
                ((ObjectNode) node).remove("nullable");
            }
            node.properties().forEach(e -> removeNullable(e.getValue(), pointer + "/" + escape(e.getKey()), removed));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) removeNullable(node.get(i), pointer + "/" + i, removed);
        }
    }

    private static void injectExtension(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.set("x-f0-probe", JSON.createObjectNode().put("enabled", true).put("name", "probe"));
        }
    }

    private static List<String> differences(JsonNode expected, JsonNode actual) {
        List<String> differences = new ArrayList<>();
        compare("", expected, actual, differences);
        return differences;
    }

    private static void compare(String path, JsonNode expected, JsonNode actual, List<String> differences) {
        if (expected.equals(actual)) return;
        if (expected.isObject() && actual.isObject()) {
            expected.properties().forEach(e -> compare(path + "/" + escape(e.getKey()), e.getValue(), actual.path(e.getKey()), differences));
            actual.properties().forEach(e -> {
                // Ignore only absent optional fields materialized as null/empty defaults by the POJO.
                if (!expected.has(e.getKey()) && !e.getValue().isNull()
                        && !(e.getValue().isContainerNode() && e.getValue().isEmpty())) {
                    differences.add(path + "/" + escape(e.getKey()) + ": added " + brief(e.getValue()));
                }
            });
        } else if (expected.isArray() && actual.isArray() && expected.size() == actual.size()) {
            for (int i = 0; i < expected.size(); i++) compare(path + "/" + i, expected.get(i), actual.get(i), differences);
        } else {
            differences.add(path + ": expected " + brief(expected) + ", actual " + brief(actual));
        }
    }

    private static String escape(String text) { return text.replace("~", "~0").replace("/", "~1"); }

    private static Object requiredEntry(Map<String, ?> values, String key) {
        Objects.requireNonNull(values, "Mapped container is missing for entry: " + key);
        return Objects.requireNonNull(values.get(key), "Mapped entry is missing: " + key);
    }

    private static String brief(JsonNode node) {
        String text = node.isMissingNode() ? "<missing>" : node.toString();
        return text.length() > 220 ? text.substring(0, 220) + "..." : text;
    }

    private static String error(Exception exception) {
        if (exception instanceof com.fasterxml.jackson.databind.JsonMappingException mapping) {
            return exception.getClass().getSimpleName() + ": " + mapping.getOriginalMessage() + "; path=" + mapping.getPathReference();
        }
        return exception.getClass().getSimpleName() + ": " + exception.getMessage();
    }
}
