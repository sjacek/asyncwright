package io.github.sjacek.asyncwright.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sjacek.asyncwright.api.Avro19;
import io.github.sjacek.asyncwright.api.Severity;
import io.github.sjacek.asyncwright.api.SourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Public parser contracts for standalone Avro, controlled file access and immutable resolver targets. */
class AvroParserTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USE = "/components/schemas/Value/schema";
    private static final String RECORD = "{\"type\":\"record\",\"name\":\"Event\",\"fields\":[{\"name\":\"id\",\"type\":\"long\"}]}";
    private final AsyncApiParser parser = new AsyncApiParser();
    @TempDir
    Path root;

    private static String document(String schema) {
        return """
                {"asyncapi":"3.0.0","info":{"title":"Avro","version":"1"},"components":{"schemas":{
                  "Value":{"schemaFormat":"application/vnd.apache.avro;version=1.9.0","schema":%s}}}}
                """.formatted(schema);
    }

    private ParseResult parse(String schema) { return parser.parse("events.json", document(schema)); }

    private String reference(String file) throws Exception {
        return JSON.writeValueAsString(JSON.createObjectNode().put("$ref", file));
    }

    private void file(String name, String content) throws Exception {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private ParseResult external(String reference, Set<String> files) throws Exception {
        return parser.parse("events.json", document(reference(reference)), new ParseContext(root, files));
    }

    @ParameterizedTest
    @EnumSource(Avro19.Format.class)
    void acceptsAllThreeVersionedFormatsWithoutRewritingTheSource(Avro19.Format format) {
        String input = document(RECORD).replace(Avro19.Format.DEFAULT.mediaType(), format.mediaType());
        var result = parser.parse("events.yaml", input);
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(format.mediaType(), result.document().orElseThrow().source().at("/components/schemas/Value/schemaFormat").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"string\"", "\"null\"", "[\"null\",\"long\"]",
            RECORD,
            "{\"type\":\"record\",\"name\":\"Node\",\"fields\":[{\"name\":\"next\",\"type\":[\"null\",\"Node\"],\"default\":null}]}",
            "{\"type\":\"bytes\",\"logicalType\":\"decimal\",\"precision\":9,\"scale\":2}",
            "{\"type\":\"int\",\"logicalType\":\"date\"}",
            "{\"type\":\"string\",\"logicalType\":\"uuid\"}",
            "{\"type\":\"int\",\"logicalType\":\"time-millis\"}",
            "{\"type\":\"long\",\"logicalType\":\"time-micros\"}",
            "{\"type\":\"long\",\"logicalType\":\"timestamp-micros\"}",
            "{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}",
            "{\"type\":\"record\",\"name\":\"example.Node\",\"namespace\":\"ignored\",\"fields\":[{\"name\":\"next\",\"type\":[\"null\",\"example.Node\"],\"default\":null}]}",
            "{\"type\":\"record\",\"name\":\"Outer\",\"fields\":[{\"name\":\"child\",\"type\":{\"type\":\"record\",\"name\":\"Child\",\"fields\":[{\"name\":\"n\",\"type\":\"int\",\"default\":1}]},\"default\":{}}]}",
            "{\"type\":\"fixed\",\"name\":\"Interval\",\"size\":12,\"logicalType\":\"duration\"}",
            "{\"type\":\"record\",\"name\":\"WithDefaults\",\"aliases\":[\"Previous\"],\"doc\":\"Docs\",\"fields\":[{\"name\":\"value\",\"type\":\"long\",\"default\":9223372036854775807,\"aliases\":[\"old\"],\"extra\":{\"$ref\":\"literal.json\"}}]}",
            "{\"type\":\"record\",\"name\":\"MapDefault\",\"fields\":[{\"name\":\"map\",\"type\":{\"type\":\"map\",\"values\":\"string\"},\"default\":{\"$ref\":\"data.json\"}}]}",
            "{\"type\":\"string\",\"$ref\":\"metadata.json\",\"metadata\":{\"schemaFormat\":\"ignored\",\"type\":\"record\"}}"
    })
    void validatesSchemasWithoutFollowingNamesOrMetadataAsJsonReferences(String schema) throws Exception {
        var result = parse(schema);
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(JSON.readTree(schema), JSON.readTree(result.document().orElseThrow().references().resolve(USE).orElseThrow().node().toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "true", "123", "\"Unknown\"",
            "{\"type\":\"record\",\"name\":\"Container\",\"fields\":[{\"name\":\"first\",\"type\":{\"type\":\"record\",\"name\":\"Inner\",\"fields\":[]}},{\"name\":\"second\",\"type\":{\"type\":\"Inner\"}}]}",
            "{\"type\":\"record\",\"name\":\"Bad-Name\",\"fields\":[]}",
            "{\"type\":\"record\",\"name\":\"Żaba\",\"fields\":[]}",
            "{\"type\":\"record\",\"name\":\"Event\",\"namespace\":\"bad-namespace\",\"fields\":[]}",
            "{\"type\":\"record\",\"name\":\"Event\",\"fields\":[{\"name\":\"id\",\"type\":\"long\"},{\"name\":\"id\",\"type\":\"long\"}]}",
            "[\"string\",\"string\"]", "[[\"null\",\"string\"],\"long\"]",
            "{\"type\":\"record\",\"name\":\"Event\",\"fields\":[{\"name\":\"id\",\"type\":\"int\",\"default\":2147483648}]}",
            "{\"type\":\"record\",\"name\":\"Event\",\"fields\":[{\"name\":\"id\",\"type\":\"long\",\"default\":9223372036854775808}]}",
            "{\"type\":\"record\",\"name\":\"Event\",\"fields\":[{\"name\":\"id\",\"type\":[\"null\",\"string\"],\"default\":\"later-branch\"}]}",
            "{\"type\":\"record\",\"name\":\"Event\",\"fields\":[{\"name\":\"id\",\"type\":{\"type\":\"enum\",\"name\":\"State\",\"symbols\":[\"OK\"]},\"default\":\"UNKNOWN\"}]}",
            "{\"type\":\"record\",\"name\":\"Event\",\"fields\":[{\"name\":\"id\",\"type\":{\"type\":\"fixed\",\"name\":\"Id\",\"size\":2},\"default\":\"x\"}]}",
            "{\"type\":\"record\",\"name\":\"Event\",\"fields\":[{\"name\":\"id\",\"type\":\"bytes\",\"default\":\"Ż\"}]}",
            "{\"type\":\"record\",\"name\":\"Event\",\"fields\":[{\"name\":\"values\",\"type\":{\"type\":\"array\",\"items\":\"long\"},\"default\":[1,\"wrong\"]}]}",
            "{\"type\":\"record\",\"name\":\"Event\",\"fields\":[{\"name\":\"values\",\"type\":{\"type\":\"map\",\"values\":\"string\"},\"default\":{\"key\":false}}]}",
            "{\"type\":\"record\",\"name\":\"Outer\",\"fields\":[{\"name\":\"child\",\"type\":{\"type\":\"record\",\"name\":\"Child\",\"fields\":[{\"name\":\"n\",\"type\":\"int\"}]},\"default\":{}}]}",
            "{\"type\":\"bytes\",\"logicalType\":\"decimal\",\"precision\":2,\"scale\":3}",
            "{\"type\":\"long\",\"logicalType\":\"date\"}",
            "{\"type\":\"fixed\",\"name\":\"Wrong\",\"size\":4,\"logicalType\":\"duration\"}",
            "{\"type\":\"string\",\"logicalType\":12}"
    })
    void rejectsInvalidAvroBeforeTypedMapping(String schema) {
        var result = parse(schema);
        assertFalse(result.successful(), result.diagnostics()::toString);
        assertTrue(result.document().isEmpty());
        assertTrue(result.diagnostics().stream().noneMatch(d -> d.ruleId().startsWith("model.")), result.diagnostics()::toString);
    }

    @ParameterizedTest
    @ValueSource(strings = {"custom-logical", "timestamp-nanos", "local-timestamp-millis"})
    void unknownAndPost19LogicalTypesWarnWhilePreservingTheirMetadata(String name) {
        var result = parse("{\"type\":\"long\",\"logicalType\":\"" + name + "\"}");
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertEquals(1, result.diagnostics().size());
        assertEquals(Severity.WARNING, result.diagnostics().get(0).severity());
        assertEquals(USE + "/logicalType", result.diagnostics().get(0).pointer());
        assertEquals(name, result.document().orElseThrow().source().at(USE + "/logicalType").asText());
    }

    @Test
    void fileAuthorityIsExplicitAndSnapshotsAreImmutable() throws Exception {
        file("avro/event.avsc", RECORD);
        var files = new HashSet<>(Set.of("avro/event.avsc"));
        var context = new ParseContext(root, files);
        files.clear();
        var result = parser.parse("events.json", document(reference("avro/event.avsc")), context);
        assertTrue(result.successful(), result.diagnostics()::toString);
        var parsed = result.document().orElseThrow();
        var target = parsed.references().resolveSchemaFile(USE).orElseThrow();
        assertEquals("avro/event.avsc", target.file());
        ((ObjectNode) target.node()).removeAll();
        file("avro/event.avsc", "broken after parsing");
        assertEquals("record", parsed.references().resolveSchemaFile(USE).orElseThrow().node().path("type").asText());
        assertTrue(parsed.references().resolve(USE).isEmpty(), "A file target is not a document JSON Pointer");
        assertEquals("avro/event.avsc", parsed.source().at(USE + "/$ref").asText());
        assertEquals("avro/event.avsc", JSON.valueToTree(parsed.model()).at(USE + "/$ref").asText());
    }

    @Test
    void legacyMethodsDoNotReadAvroFilesAndExplainTheContextOverload() throws Exception {
        file("avro/event.avsc", RECORD);
        file("events.json", document(reference("avro/event.avsc")));
        for (ParseResult result : List.of(parser.parse("events.json", document(reference("avro/event.avsc"))),
                parser.parse(root, Path.of("events.json")))) {
            assertFalse(result.successful());
            assertEquals("unsupported.reference", result.diagnostics().get(0).ruleId());
            assertTrue(result.diagnostics().get(0).message().contains("ParseContext"));
        }
    }

    @Test
    void resolvesRelativeToTheDocumentPreservingPlusSignsAndPercentDecodedSpaces() throws Exception {
        String file = "schemas/order+ event.avsc";
        file(file, RECORD);
        var context = new ParseContext(root, Set.of(file));
        var result = parser.parse("specs/events.yaml", document(reference("../schemas/order+%20event.avsc")), context);
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertEquals(file, result.document().orElseThrow().references().resolveSchemaFile(USE).orElseThrow().file());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "#/components/schemas/Value", "/tmp/a.avsc", "C:/tmp/a.avsc", "file:avro/a.avsc", "https://example.invalid/a.avsc",
            "//example.invalid/a.avsc", "../a.avsc", "%2e%2e/a.avsc", "%2Fa.avsc", "%5Cevil.avsc",
            "avro/a.avsc#", "avro/a.avsc#/type", "avro/a.avsc?x=1", "avro/a.json", "avro/not-declared.avsc"})
    void rejectsUnsafeOrUndeclaredReferencesBeforeReading(String reference) throws Exception {
        var result = external(reference, Set.of("avro/a.avsc"));
        assertFalse(result.successful());
        assertEquals(reference.contains("#") ? "unsupported.reference" : "avro.reference",
                result.diagnostics().get(0).ruleId(), result.diagnostics()::toString);
        assertEquals(USE + "/$ref", result.diagnostics().get(0).pointer());
        assertFalse(result.diagnosticsJson().contains(root.toString().replace("\\", "\\\\")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{\"type\":\"long\"} {}", "{\"type\":\"long\",\"type\":\"string\"}", "type: string", "\"UnknownType\""})
    void rejectsInvalidExternalJsonOrSchemasAtTheFileWithReferenceUses(String input) throws Exception {
        file("avro/event.avsc", input);
        var result = external("avro/event.avsc", Set.of("avro/event.avsc"));
        assertFalse(result.successful());
        assertTrue(result.document().isEmpty());
        assertEquals("avro/event.avsc", result.diagnostics().get(0).file());
        assertEquals(USE + "/$ref", result.diagnostics().get(0).related().get(0).pointer());
    }

    @Test
    void missingDeclaredFileFailsButUnusedMissingFilesAreNeverRead() throws Exception {
        assertFalse(external("avro/missing.avsc", Set.of("avro/missing.avsc")).successful());
        assertTrue(parser.parse("events.json", document(RECORD), new ParseContext(root, Set.of("unused.avsc"))).successful());
    }

    @Test
    void pathOverloadReadsOnlyAMainDocumentWithinTheContext() throws Exception {
        file("contracts/events.json", document(reference("../avro/event.avsc")));
        file("avro/event.avsc", RECORD);
        var context = new ParseContext(root, Set.of("avro/event.avsc"));
        var result = parser.parse(Path.of("contracts/events.json"), context);
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertTrue(result.document().orElseThrow().references().resolveSchemaFile(USE).isPresent());
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(root.resolve("../outside.json"), context));
    }

    @Test
    void eachFileAndWrapperHasItsOwnNamedTypeScope() throws Exception {
        file("avro/defined.avsc", RECORD);
        file("avro/reference.avsc", "\"Event\"");
        var input = (ObjectNode) JSON.readTree(document(reference("avro/defined.avsc")));
        object(input, "/components/schemas").set("Other", JSON.readTree("{\"schemaFormat\":\"application/vnd.apache.avro;version=1.9.0\",\"schema\":{\"$ref\":\"avro/reference.avsc\"}}"));
        var result = parser.parse("events.json", input.toString(), new ParseContext(root, Set.of("avro/defined.avsc", "avro/reference.avsc")));
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.file().equals("avro/reference.avsc") && d.ruleId().equals("avro.schema")));
    }

    @Test
    void sharedFileWarningsAreReportedOnceWithAllDocumentUses() throws Exception {
        file("avro/event.avsc", "{\"type\":\"long\",\"logicalType\":\"custom\"}");
        var input = (ObjectNode) JSON.readTree(document(reference("avro/event.avsc")));
        object(input, "/components/schemas").set("Other", object(input, "/components/schemas/Value").deepCopy());
        var result = parser.parse("events.json", input.toString(), new ParseContext(root, Set.of("avro/event.avsc")));
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertEquals(1, result.diagnostics().size());
        var diagnostic = result.diagnostics().get(0);
        assertEquals("avro/event.avsc", diagnostic.file());
        assertEquals("/logicalType", diagnostic.pointer());
        assertEquals(Set.of(USE + "/$ref", "/components/schemas/Other/schema/$ref"),
                Set.copyOf(diagnostic.related().stream().map(SourceLocation::pointer).toList()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void kafkaKeyUsesAReferenceToAnAvroComponentWithoutExpandingTheModel(boolean external) throws Exception {
        file("avro/event.avsc", RECORD);
        var input = (ObjectNode) JSON.readTree(document(external ? reference("avro/event.avsc") : RECORD));
        input.putObject("channels").putObject("events").put("address", "events").putObject("messages")
                .putObject("event").putObject("bindings").putObject("kafka").put("bindingVersion", "0.5.0")
                .set("key", JSON.createObjectNode().put("$ref", "#/components/schemas/Value"));
        var result = parser.parse("events.json", input.toString(), new ParseContext(root, Set.of("avro/event.avsc")));
        assertTrue(result.successful(), result.diagnostics()::toString);
        String key = "/channels/events/messages/event/bindings/kafka/key";
        var parsed = result.document().orElseThrow();
        assertEquals("/components/schemas/Value", parsed.references().resolve(key).orElseThrow().pointer());
        assertEquals("#/components/schemas/Value", JSON.valueToTree(parsed.model()).at(key + "/$ref").asText());
        assertEquals(external, parsed.references().resolveSchemaFile(USE).isPresent());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realPathBoundaryAllowsInternalLinksAndRejectsExternalLinks(boolean inside) throws Exception {
        Path target = inside ? root.resolve("real.avsc") : Files.createTempFile(root.getParent(), "outside-avro-", ".avsc");
        try {
            Files.writeString(target, RECORD);
            try { Files.createSymbolicLink(root.resolve("link.avsc"), target); }
            catch (UnsupportedOperationException | java.io.IOException exception) { assumeTrue(false, "Symbolic links unavailable on this host"); }
            var result = external("link.avsc", Set.of("link.avsc"));
            assertEquals(inside, result.successful(), result.diagnostics()::toString);
            if (!inside) assertEquals("avro.reference", result.diagnostics().get(0).ruleId());
        } finally { if (!inside) Files.deleteIfExists(target); }
    }

    private static ObjectNode object(JsonNode root, String pointer) { return assertInstanceOf(ObjectNode.class, root.at(pointer)); }
}
