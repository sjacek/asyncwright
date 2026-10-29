package io.github.sjacek.asyncwright.parser;

import com.asyncapi.v3._0_0.model.AsyncAPI;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.regex.JoniRegularExpressionFactory;
import io.github.sjacek.asyncwright.api.AsyncApi30;
import io.github.sjacek.asyncwright.api.AsyncApiFields;
import io.github.sjacek.asyncwright.api.BuiltinRuleId;
import io.github.sjacek.asyncwright.api.Diagnostic;
import io.github.sjacek.asyncwright.api.Severity;
import io.github.sjacek.asyncwright.api.SourceLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static io.github.sjacek.asyncwright.api.AsyncApiFields.ADDRESS;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.ASYNCAPI;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.CHANNEL;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.CHANNELS;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.MESSAGES;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.OPERATIONS;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.PARAMETERS;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.REF;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.SERVERS;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.TYPE;

/** Gradle-independent AsyncAPI 3.0 parser; schema retrieval never uses the network. */
public final class AsyncApiParser {
    /** Creates a parser using the bundled AsyncAPI 3.0 schema and model mapping. */
    public AsyncApiParser() { }

    private static final ObjectMapper MAPPER = ModelMapping.mapper();
    private static final Pattern PARAMETER = Pattern.compile("\\{([^{}]+)}");
    private static final Schema SCHEMA = schema();

    private static Schema schema() {
        String id = AsyncApi30.SCHEMA_ID;
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SchemaCompatibility.dialect(), builder -> builder
                .schemaRegistryConfig(SchemaRegistryConfig.builder().locale(Locale.ENGLISH)
                        .formatAssertionsEnabled(true).regularExpressionFactory(JoniRegularExpressionFactory.getInstance()).build())
                .schemaIdResolvers(r -> r.mappings(Map.of(id, "classpath:" + AsyncApi30.SCHEMA_RESOURCE.substring(1))))
                .schemaLoader(loader -> loader.fetchRemoteResources(false)));
        Schema schema = registry.getSchema(SchemaLocation.of(id), SchemaCompatibility.schema());
        schema.initializeValidators();
        return schema;
    }

    /**
     * Reads UTF-8 JSON or YAML within a project directory and validates it offline.
     * @param projectRoot base directory for portable diagnostic file names
     * @param file absolute file path or a path relative to the project directory
     * @return validated model and diagnostics, or diagnostics without a model on invalid input
     * @throws IOException if the file cannot be read
     * @throws IllegalArgumentException if the normalized file path is outside the project directory
     */
    public ParseResult parse(Path projectRoot, Path file) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path absolute = file.isAbsolute() ? file.normalize() : root.resolve(file).normalize();
        if (!absolute.startsWith(root)) throw new IllegalArgumentException("Document must be inside the project root");
        return parse(root.relativize(absolute).toString().replace('\\', '/'), Files.readString(absolute));
    }

    /**
     * Validates one JSON or YAML document without reading external resources.
     * @param relativeFile project-relative diagnostic name; backslashes are normalized
     * @param input complete document text
     * @return validated model and diagnostics, or diagnostics without a model on invalid input
     * @throws IllegalArgumentException if the diagnostic file name is not a portable relative path
     */
    public ParseResult parse(String relativeFile, String input) {
        return parseInput(relativeFile, input, null);
    }

    /**
     * Parses text with explicit authority for declared local Avro files.
     * @param relativeFile project-relative diagnostic file name
     * @param input complete JSON or YAML document
     * @param context project root and immutable schema file allowlist
     * @return validated document and diagnostics; external failures never return a partial model
     */
    public ParseResult parse(String relativeFile, String input, ParseContext context) {
        return parseInput(relativeFile, input, Objects.requireNonNull(context, "context"));
    }

    /**
     * Reads the main document and declared local Avro files within one explicit context.
     * @param file document path, absolute inside the root or relative to it
     * @param context project root and allowed schema file names
     * @return parser outcome
     * @throws IOException if the main document cannot be read
     * @throws IllegalArgumentException if the document is outside the context root
     */
    public ParseResult parse(Path file, ParseContext context) throws IOException {
        Path root = context.projectRoot();
        Path absolute = file.isAbsolute() ? file.normalize() : root.resolve(file).normalize();
        if (!absolute.startsWith(root)) throw new IllegalArgumentException("Document must be inside the project root");
        if (!absolute.toRealPath().startsWith(root.toRealPath()))
            throw new IllegalArgumentException("Document real path must be inside the project root");
        return parse(root.relativize(absolute).toString().replace('\\', '/'), Files.readString(absolute), context);
    }

    private ParseResult parseInput(String relativeFile, String input, ParseContext context) {
        Path path = DirectoryOutput.relativePath(relativeFile);
        String file = path.toString().replace('\\', '/');
        if (file.isBlank()) throw new IllegalArgumentException("Expected a document file name");
        SourceDocument source;
        try { source = SourceDocument.read(file, input); }
        catch (SourceDocument.UnsupportedAlias exception) {
            return failed(List.of(Diagnostic.at(BuiltinRuleId.UNSUPPORTED_YAML_ALIAS, Severity.ERROR,
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
            return failed(List.of(Diagnostic.at(BuiltinRuleId.SYNTAX, Severity.ERROR, message,
                    new SourceLocation(file, line, column, ""), List.of())));
        }
        DocumentIndex index = new DocumentIndex(source);
        ReferenceResolver references = new ReferenceResolver(source, index);
        List<Diagnostic> diagnostics = new ArrayList<>();
        JsonNode version = source.root.path(ASYNCAPI);
        if (version.isTextual() && !version.asText().equals(AsyncApi30.VERSION))
            return failed(List.of(Diagnostic.at(BuiltinRuleId.UNSUPPORTED_VERSION, Severity.ERROR,
                    "Only AsyncAPI " + AsyncApi30.VERSION + " is supported in 0.1", source.location("/" + ASYNCAPI), List.of())));
        JsonNode structural = source.root;
        if (!index.avroSchemas.isEmpty()) {
            structural = source.root.deepCopy();
            for (String pointer : index.avroSchemas) {
                JsonNode value = structural.at(pointer);
                if (value.has(REF) && !value.has(TYPE)) continue;
                // The bundled JSON Schema's Avro approximation rejects legal full names and
                // named object references. Validate this opaque leaf with the Avro grammar;
                // retain structural validation of its enclosing Multi Format Schema Object.
                int slash = pointer.lastIndexOf('/');
                ((ObjectNode) structural.at(pointer.substring(0, slash)))
                        .put(AsyncApiFields.SCHEMA, "string");
            }
        }
        for (var error : SCHEMA.validate(structural)) {
            String pointer = error.getInstanceLocation().toString();
            diagnostics.add(Diagnostic.at(BuiltinRuleId.schemaKeyword(error.getKeyword()), Severity.ERROR, error.getMessage(),
                    source.location(pointer), references.related(pointer)));
        }
        // Later stages assume structurally valid objects; stop here to avoid misleading secondary findings.
        if (hasErrors(diagnostics)) return failed(diagnostics);
        for (Diagnostic diagnostic : index.diagnostics)
            diagnostics.add(Diagnostic.at(diagnostic.ruleId(), diagnostic.severity(), diagnostic.message(),
                    source.location(diagnostic.pointer()), references.related(diagnostic.pointer())));
        if (hasErrors(diagnostics)) return failed(diagnostics);
        diagnostics.addAll(references.validate());
        diagnostics.addAll(new AvroValidation(source, index, references, context).validate());
        semanticRules(source, index, references, diagnostics);
        // Registry checks read validated source/resolver contexts, not an expanded typed model.
        // Reference errors can coexist with findings from independent operations or replies.
        diagnostics.addAll(new KafkaSchemaRegistryRule(source, index, references).validate());
        if (hasErrors(diagnostics)) return failed(diagnostics);
        // Reference siblings are ignored by the specification; preserve the original source separately.
        JsonNode mapping = references.mappingTree("", source.root);
        try {
            AsyncAPI model = MAPPER.treeToValue(mapping, AsyncAPI.class);
            List<String> losses = new ArrayList<>();
            checkPreservation(mapping, MAPPER.valueToTree(model), "", losses);
            if (!losses.isEmpty()) {
                for (String pointer : losses) diagnostics.add(Diagnostic.at(BuiltinRuleId.MODEL_PRESERVATION, Severity.ERROR,
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
            diagnostics.add(Diagnostic.at(BuiltinRuleId.MODEL_MAPPING, Severity.ERROR, exception.getOriginalMessage(),
                    source.location(pointer), references.related(pointer)));
            return failed(diagnostics);
        }
    }

    static void checkPreservation(JsonNode expected, JsonNode actual, String pointer, List<String> losses) {
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
            if (node.has(REF)) continue;
            if (entry.getValue() == DocumentIndex.Kind.CHANNEL) {
                Set<String> parameters = new LinkedHashSet<>();
                var matcher = PARAMETER.matcher(node.path(ADDRESS).asText(""));
                while (matcher.find()) parameters.add(matcher.group(1));
                for (String parameter : parameters) if (!node.path(PARAMETERS).has(parameter))
                    diagnostics.add(Diagnostic.at(BuiltinRuleId.CHANNEL_PARAMETER, Severity.ERROR,
                            "Undefined channel address parameter: " + parameter,
                            source.location(pointer + "/" + ADDRESS), references.related(pointer + "/" + ADDRESS)));
                for (var parameter : node.path(PARAMETERS).properties()) if (!parameters.contains(parameter.getKey())) {
                    String definition = pointer + "/" + PARAMETERS + "/" + SourceDocument.escape(parameter.getKey());
                    diagnostics.add(Diagnostic.at(BuiltinRuleId.CHANNEL_PARAMETER, Severity.ERROR,
                            "Channel parameter is absent from the address: " + parameter.getKey(),
                            source.location(definition), references.related(definition)));
                }
                if (isRootMember(pointer, CHANNELS)) {
                    for (int i = 0; i < node.path(SERVERS).size(); i++)
                        validateRootReference(source, references, pointer + "/" + SERVERS + "/" + i, SERVERS, BuiltinRuleId.CHANNEL_SERVER,
                                "Root channels must reference servers under /servers", diagnostics);
                }
            }
            if (entry.getValue() != DocumentIndex.Kind.OPERATION) continue;
            if (isRootMember(pointer, OPERATIONS))
                validateRootReference(source, references, pointer + "/" + CHANNEL, CHANNELS, BuiltinRuleId.OPERATION_CHANNEL,
                        "Root operations must reference channels under /channels", diagnostics);
            var channel = references.resolve(pointer + "/" + CHANNEL);
            if (channel.isEmpty()) continue;
            Set<String> messages = new HashSet<>();
            boolean completeMessages = true;
            for (var message : channel.get().node().path(MESSAGES).properties()) {
                var target = references.resolve(channel.get().pointer() + "/" + MESSAGES + "/" + SourceDocument.escape(message.getKey()));
                if (target.isPresent()) messages.add(target.get().pointer());
                else completeMessages = false;
            }
            // An unresolved member makes the channel's message set unknown, not empty.
            if (!completeMessages) continue;
            JsonNode operationMessages = node.path(MESSAGES);
            for (int i = 0; i < operationMessages.size(); i++) {
                String use = pointer + "/" + MESSAGES + "/" + i;
                references.resolve(use).ifPresent(message -> {
                    if (!messages.contains(message.pointer())) diagnostics.add(Diagnostic.at(BuiltinRuleId.OPERATION_MESSAGE, Severity.ERROR,
                            "Operation message does not belong to its channel", source.location(use + "/" + REF),
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
                                              String collection, BuiltinRuleId rule, String message, List<Diagnostic> diagnostics) {
        references.directTarget(use).filter(target -> !isRootMember(target, collection)).ifPresent(target ->
                diagnostics.add(Diagnostic.at(rule, Severity.ERROR, message, source.location(use + "/" + REF),
                        List.of(source.location(target)))));
    }

    private static boolean hasErrors(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }

    private static ParseResult failed(List<Diagnostic> diagnostics) {
        return new ParseResult(Optional.empty(), diagnostics);
    }
}
