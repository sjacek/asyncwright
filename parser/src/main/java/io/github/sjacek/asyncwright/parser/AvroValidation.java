package io.github.sjacek.asyncwright.parser;

import com.asyncapi.schemas.asyncapi.multiformat.AvroFormatSchema;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sjacek.asyncwright.api.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static io.github.sjacek.asyncwright.api.AsyncApiFields.*;

/**
 * Coordinates inline validation and explicitly authorized local Avro files for one parse.
 * Discovery comes from typed document positions, not arbitrary $ref-shaped data. Targets are
 * grouped by normalized project-relative name so one file has one validation result with all
 * document usages attached. Only successfully validated and faithfully mapped targets enter
 * the resolver's snapshot table; the source/model always retain their original references.
 */
final class AvroValidation {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ObjectMapper MODEL = ModelMapping.mapper();
    private final SourceDocument source;
    private final DocumentIndex index;
    private final ReferenceResolver references;
    private final ParseContext context;
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    AvroValidation(SourceDocument source, DocumentIndex index, ReferenceResolver references, ParseContext context) {
        this.source = source;
        this.index = index;
        this.references = references;
        this.context = context;
    }

    /** Validates inline leaves immediately, then reads each declared target once for this parse. */
    List<Diagnostic> validate() {
        Map<String, List<String>> uses = new LinkedHashMap<>();
        Map<String, Path> paths = new LinkedHashMap<>();
        for (String pointer : index.avroSchemas) {
            JsonNode schema = source.root.at(pointer);
            if (schema.has(REF) && !schema.has(TYPE)) {
                if (context == null) {
                    diagnostics.add(Diagnostic.at(BuiltinRuleId.UNSUPPORTED_REFERENCE, Severity.ERROR,
                            "Local Avro files require parse(relativeFile, input, ParseContext) with declared schemaFiles",
                            source.location(pointer + "/" + REF), references.related(pointer)));
                    continue;
                }
                try {
                    String file = fileName(schema.path(REF).asText());
                    uses.computeIfAbsent(file, ignored -> new ArrayList<>()).add(pointer);
                    paths.putIfAbsent(file, context.projectRoot().resolve(file));
                } catch (IllegalArgumentException exception) {
                    var rule = schema.path(REF).asText().contains("#")
                            ? BuiltinRuleId.UNSUPPORTED_REFERENCE : BuiltinRuleId.AVRO_REFERENCE;
                    diagnostics.add(Diagnostic.at(rule, Severity.ERROR,
                            exception.getMessage(), source.location(pointer + "/" + REF), references.related(pointer)));
                }
            } else validateSchema(source, pointer, references.related(pointer));
        }
        for (var entry : uses.entrySet()) validateFile(entry.getKey(), paths.get(entry.getKey()), entry.getValue());
        return List.copyOf(diagnostics);
    }

    /**
     * Converts an allowed whole-file URI into its project-relative allowlist key without I/O.
     * Resolution starts at the main document's directory, not the process working directory.
     * URI decoding preserves plus signs; normalized traversal inside the root is permitted.
     * Real-path/symlink checks occur separately immediately before reading the file.
     */
    private String fileName(String reference) {
        URI uri;
        try { uri = URI.create(reference); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Malformed Avro file reference"); }
        String path = uri.getPath();
        if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\")
                || path.matches("^[a-zA-Z]:.*"))
            throw new IllegalArgumentException("Expected a relative Avro .avsc file reference without URI scheme, query or fragment");
        Path root = context.projectRoot();
        Path target = root.resolve(source.file).getParent().resolve(path).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Avro file reference escapes the project root");
        String file = root.relativize(target).toString().replace('\\', '/');
        if (!file.endsWith(".avsc")) throw new IllegalArgumentException("Only Avro .avsc files may be referenced");
        if (!context.schemaFiles().contains(file)) throw new IllegalArgumentException("Avro file is not declared in schemaFiles: " + file);
        return file;
    }

    /** Read a shared target once, and attach every direct/transitive usage to its diagnostics. */
    private void validateFile(String file, Path path, List<String> uses) {
        Set<SourceLocation> locations = new LinkedHashSet<>();
        for (String use : uses) {
            locations.add(source.location(use + "/" + REF));
            locations.addAll(references.related(use));
        }
        List<SourceLocation> related = locations.stream().sorted(Comparator.comparing(SourceLocation::pointer)).toList();
        SourceDocument document;
        try {
            Path realRoot = context.projectRoot().toRealPath();
            Path real = path.toRealPath();
            if (!real.startsWith(realRoot) || !Files.isRegularFile(real)) {
                diagnostics.add(Diagnostic.at(BuiltinRuleId.AVRO_REFERENCE, Severity.ERROR,
                        "Avro schema must be a regular file whose real path is inside the project root",
                        source.location(uses.get(0) + "/" + REF), related));
                return;
            }
            String input = Files.readString(real);
            JsonNode parsed = JSON.readTree(input);
            if (parsed == null || parsed.isMissingNode()) {
                diagnostics.add(Diagnostic.at(BuiltinRuleId.AVRO_SCHEMA, Severity.ERROR,
                        "Expected one JSON Avro schema; the file is empty", new SourceLocation(file, 1, 1, ""), related));
                return;
            }
            document = SourceDocument.read(file, input);
        } catch (JsonProcessingException exception) {
            var at = exception.getLocation();
            diagnostics.add(Diagnostic.at(BuiltinRuleId.AVRO_SCHEMA, Severity.ERROR,
                    "Expected one JSON Avro schema: " + exception.getOriginalMessage(),
                    new SourceLocation(file, at == null ? 1 : Math.max(1, at.getLineNr()),
                            at == null ? 1 : Math.max(1, at.getColumnNr()), ""), related));
            return;
        } catch (IOException exception) {
            // Do not expose absolute OS paths from filesystem/encoding exceptions in reports.
            diagnostics.add(Diagnostic.at(BuiltinRuleId.AVRO_REFERENCE, Severity.ERROR,
                    "Cannot read the declared UTF-8 Avro schema file: " + file,
                    source.location(uses.get(0) + "/" + REF), related));
            return;
        }
        int before = diagnostics.size();
        validateSchema(document, "", related);
        if (diagnostics.subList(before, diagnostics.size()).stream().anyMatch(d -> d.severity() == Severity.ERROR)) return;
        // A file is not substituted into the document model. Guard its own typed representation
        // separately, so a successful external snapshot cannot hide model data loss.
        try {
            var wrapper = MODEL.createObjectNode().put(SCHEMA_FORMAT, Avro19.Format.DEFAULT.mediaType());
            wrapper.set(SCHEMA, document.root);
            JsonNode mapped = MODEL.valueToTree(MODEL.treeToValue(wrapper, AvroFormatSchema.class));
            List<String> losses = new ArrayList<>();
            AsyncApiParser.checkPreservation(document.root, mapped.path(SCHEMA), "", losses);
            for (String loss : losses) diagnostics.add(Diagnostic.at(BuiltinRuleId.MODEL_PRESERVATION, Severity.ERROR,
                    "The typed Avro model cannot preserve this source value", document.location(loss), related));
            if (!losses.isEmpty()) return;
        } catch (JsonProcessingException exception) {
            diagnostics.add(Diagnostic.at(BuiltinRuleId.MODEL_MAPPING, Severity.ERROR,
                    "Cannot map the validated Avro schema: " + exception.getOriginalMessage(), document.location(""), related));
            return;
        }
        // Registration happens after the model guard. Warnings permit a snapshot; errors do not.
        for (String use : uses) references.schemaFile(use, new ResolvedSchemaFile(file, document.root));
    }

    private void validateSchema(SourceDocument document, String pointer, List<SourceLocation> related) {
        for (var problem : new AvroSchemaValidation().validate(document.root.at(pointer)))
            diagnostics.add(Diagnostic.at(problem.rule(), problem.severity(), problem.message(),
                    document.location(pointer + problem.pointer()), related));
    }
}
