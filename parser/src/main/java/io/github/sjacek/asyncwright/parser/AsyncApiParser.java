package io.github.sjacek.asyncwright.parser;

import com.asyncapi.v3._0_0.model.AsyncAPI;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.*;
import com.networknt.schema.regex.JoniRegularExpressionFactory;
import io.github.sjacek.asyncwright.api.Diagnostic;
import io.github.sjacek.asyncwright.api.Severity;
import io.github.sjacek.asyncwright.api.SourceLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** Gradle-independent AsyncAPI 3.0 parser; schema retrieval never uses the network. */
public final class AsyncApiParser {
    private static final ObjectMapper MAPPER = ModelMapping.mapper();
    private static final Pattern PARAMETER = Pattern.compile("\\{([^{}]+)}");
    private static final Schema SCHEMA = schema();

    private static Schema schema() {
        String id = "http://asyncapi.com/definitions/3.0.0/asyncapi.json";
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7, builder -> builder
                .schemaRegistryConfig(SchemaRegistryConfig.builder().locale(Locale.ENGLISH)
                        .formatAssertionsEnabled(true).regularExpressionFactory(JoniRegularExpressionFactory.getInstance()).build())
                .schemaIdResolvers(r -> r.mappings(Map.of(id, "classpath:schemas/asyncapi/3.0.0.json")))
                .schemaLoader(loader -> loader.fetchRemoteResources(false)));
        Schema schema = registry.getSchema(SchemaLocation.of(id), SchemaCompatibility.schema());
        schema.initializeValidators();
        return schema;
    }

    public ParseResult parse(Path projectRoot, Path file) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path absolute = file.isAbsolute() ? file.normalize() : root.resolve(file).normalize();
        if (!absolute.startsWith(root)) throw new IllegalArgumentException("Document must be inside the project root");
        return parse(root.relativize(absolute).toString().replace('\\', '/'), Files.readString(absolute));
    }

    public ParseResult parse(String relativeFile, String input) {
        Path path = DirectoryOutput.relativePath(relativeFile);
        String file = path.toString().replace('\\', '/');
        if (file.isBlank()) throw new IllegalArgumentException("Expected a document file name");
        SourceDocument source;
        try { source = SourceDocument.read(file, input); }
        catch (SourceDocument.UnsupportedAlias exception) {
            return failed(List.of(Diagnostic.at("unsupported.yaml-alias", Severity.ERROR,
                    exception.getMessage(), exception.location, List.of())));
        }
        catch (IOException exception) {
            int line = 1, column = 1;
            String message = exception.getMessage();
            if (exception instanceof JsonProcessingException json) {
                message = json.getOriginalMessage();
                if (json.getLocation() != null) {
                    line = Math.max(1, json.getLocation().getLineNr());
                    column = Math.max(1, json.getLocation().getColumnNr());
                }
            }
            return failed(List.of(Diagnostic.at("syntax", Severity.ERROR, message,
                    new SourceLocation(file, line, column, ""), List.of())));
        }
        DocumentIndex index = new DocumentIndex(source);
        ReferenceResolver references = new ReferenceResolver(source, index);
        List<Diagnostic> diagnostics = new ArrayList<>();
        JsonNode version = source.root.path("asyncapi");
        if (version.isTextual() && !version.asText().equals("3.0.0"))
            return failed(List.of(Diagnostic.at("unsupported.version", Severity.ERROR,
                    "Only AsyncAPI 3.0.0 is supported in 0.1", source.location("/asyncapi"), List.of())));
        for (var error : SCHEMA.validate(source.root)) {
            String pointer = error.getInstanceLocation().toString();
            diagnostics.add(Diagnostic.at("schema." + error.getKeyword(), Severity.ERROR, error.getMessage(),
                    source.location(pointer), references.related(pointer)));
        }
        if (hasErrors(diagnostics)) return failed(diagnostics);
        for (Diagnostic diagnostic : index.diagnostics)
            diagnostics.add(Diagnostic.at(diagnostic.ruleId(), diagnostic.severity(), diagnostic.message(),
                    source.location(diagnostic.pointer()), references.related(diagnostic.pointer())));
        if (hasErrors(diagnostics)) return failed(diagnostics);
        diagnostics.addAll(references.validate());
        semanticRules(source, index, references, diagnostics);
        if (hasErrors(diagnostics)) return failed(diagnostics);
        // Reference siblings are ignored by the specification; preserve the original source separately.
        JsonNode mapping = references.mappingTree("", source.root);
        try {
            AsyncAPI model = MAPPER.treeToValue(mapping, AsyncAPI.class);
            List<String> losses = new ArrayList<>();
            checkPreservation(mapping, MAPPER.valueToTree(model), "", losses);
            if (!losses.isEmpty()) {
                for (String pointer : losses) diagnostics.add(Diagnostic.at("model.preservation", Severity.ERROR,
                        "The typed model cannot preserve this source value", source.location(pointer), references.related(pointer)));
                return failed(diagnostics);
            }
            return new ParseResult(Optional.of(new AsyncApiDocument(model, source, references)), diagnostics);
        } catch (JsonProcessingException exception) {
            StringBuilder mappingPath = new StringBuilder();
            if (exception instanceof JsonMappingException mappingException) for (var element : mappingException.getPath())
                mappingPath.append('/').append(element.getFieldName() == null
                        ? Integer.toString(element.getIndex()) : SourceDocument.escape(element.getFieldName()));
            String pointer = mappingPath.toString();
            diagnostics.add(Diagnostic.at("model.mapping", Severity.ERROR, exception.getOriginalMessage(),
                    source.location(pointer), references.related(pointer)));
            return failed(diagnostics);
        }
    }

    private static void checkPreservation(JsonNode expected, JsonNode actual, String pointer, List<String> losses) {
        if (expected.isObject() && actual.isObject()) {
            for (var field : expected.properties()) checkPreservation(field.getValue(), actual.path(field.getKey()),
                    pointer + "/" + SourceDocument.escape(field.getKey()), losses);
        } else if (expected.isArray() && actual.isArray() && expected.size() == actual.size()) {
            for (int i = 0; i < expected.size(); i++) checkPreservation(expected.get(i), actual.get(i), pointer + "/" + i, losses);
        } else if (expected.isNumber() && actual.isNumber()) {
            if (expected.decimalValue().compareTo(actual.decimalValue()) != 0) losses.add(pointer);
        } else if (!expected.equals(actual)) losses.add(pointer);
    }

    private static void semanticRules(SourceDocument source, DocumentIndex index, ReferenceResolver references,
                                       List<Diagnostic> diagnostics) {
        for (var entry : index.kinds.entrySet()) {
            String pointer = entry.getKey();
            JsonNode node = source.root.at(pointer);
            if (node.has("$ref")) continue;
            if (entry.getValue() == DocumentIndex.Kind.CHANNEL) {
                Set<String> parameters = new LinkedHashSet<>();
                var matcher = PARAMETER.matcher(node.path("address").asText(""));
                while (matcher.find()) parameters.add(matcher.group(1));
                for (String parameter : parameters) if (!node.path("parameters").has(parameter))
                    diagnostics.add(Diagnostic.at("channel.parameter", Severity.ERROR,
                            "Undefined channel address parameter: " + parameter,
                            source.location(pointer + "/address"), references.related(pointer + "/address")));
                for (var parameter : node.path("parameters").properties()) if (!parameters.contains(parameter.getKey())) {
                    String definition = pointer + "/parameters/" + SourceDocument.escape(parameter.getKey());
                    diagnostics.add(Diagnostic.at("channel.parameter", Severity.ERROR,
                            "Channel parameter is absent from the address: " + parameter.getKey(),
                            source.location(definition), references.related(definition)));
                }
                if (isRootMember(pointer, "channels")) {
                    for (int i = 0; i < node.path("servers").size(); i++)
                        validateRootReference(source, references, pointer + "/servers/" + i, "servers", "channel.server",
                                "Root channels must reference servers under /servers", diagnostics);
                }
            }
            if (entry.getValue() != DocumentIndex.Kind.OPERATION) continue;
            if (isRootMember(pointer, "operations"))
                validateRootReference(source, references, pointer + "/channel", "channels", "operation.channel",
                        "Root operations must reference channels under /channels", diagnostics);
            var channel = references.resolve(pointer + "/channel");
            if (channel.isEmpty()) continue;
            Set<String> messages = new HashSet<>();
            boolean completeMessages = true;
            for (var message : channel.get().node().path("messages").properties()) {
                var target = references.resolve(channel.get().pointer() + "/messages/" + SourceDocument.escape(message.getKey()));
                if (target.isPresent()) messages.add(target.get().pointer());
                else completeMessages = false;
            }
            // An unresolved member makes the channel's message set unknown, not empty.
            if (!completeMessages) continue;
            JsonNode operationMessages = node.path("messages");
            for (int i = 0; i < operationMessages.size(); i++) {
                String use = pointer + "/messages/" + i;
                references.resolve(use).ifPresent(message -> {
                    if (!messages.contains(message.pointer())) diagnostics.add(Diagnostic.at("operation.message", Severity.ERROR,
                            "Operation message does not belong to its channel", source.location(use + "/$ref"),
                            List.of(source.location(channel.get().pointer()))));
                });
            }
        }
    }

    private static boolean isRootMember(String pointer, String collection) {
        String prefix = "/" + collection + "/";
        return pointer.startsWith(prefix) && pointer.indexOf('/', prefix.length()) < 0;
    }

    private static void validateRootReference(SourceDocument source, ReferenceResolver references, String use,
                                              String collection, String rule, String message, List<Diagnostic> diagnostics) {
        references.directTarget(use).filter(target -> !isRootMember(target, collection)).ifPresent(target ->
                diagnostics.add(Diagnostic.at(rule, Severity.ERROR, message, source.location(use + "/$ref"),
                        List.of(source.location(target)))));
    }

    private static boolean hasErrors(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }

    private static ParseResult failed(List<Diagnostic> diagnostics) {
        return new ParseResult(Optional.empty(), diagnostics);
    }
}
