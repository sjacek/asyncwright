package io.github.sjacek.asyncwright.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sjacek.asyncwright.api.Avro19;
import io.github.sjacek.asyncwright.api.SourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Regression cases where schema data, document aliases and file authority meet. */
class AvroBoundaryRegressionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USE = "/components/schemas/Value/schema";
    @TempDir
    Path root;

    private static ObjectNode document(String schema) throws Exception {
        return (ObjectNode) JSON.readTree("""
                {"asyncapi":"3.0.0","info":{"title":"Avro boundaries","version":"1"},"components":{"schemas":{
                  "Value":{"schemaFormat":"application/vnd.apache.avro;version=1.9.0","schema":%s}}}}
                """.formatted(schema));
    }

    @Test
    void aliasesLeadToOneFileSnapshotAndOneWarningWithAllUsages() throws Exception {
        Files.writeString(root.resolve("event.avsc"), "{\"type\":\"long\",\"logicalType\":\"project-specific\"}");
        var input = document("{\"$ref\":\"event.avsc\"}");
        var schemas = (ObjectNode) input.at("/components/schemas");
        schemas.putObject("Alias").put("$ref", "#/components/schemas/Value");
        schemas.putObject("Alias2").put("$ref", "#/components/schemas/Alias");
        input.putObject("channels").putObject("events").putObject("messages").putObject("event")
                .putObject("payload").put("$ref", "#/components/schemas/Alias2");
        var result = new AsyncApiParser().parse("events.json", input.toString(), new ParseContext(root, Set.of("event.avsc")));
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertEquals(1, result.diagnostics().size());
        var diagnostic = result.diagnostics().get(0);
        assertEquals("event.avsc", diagnostic.file());
        assertEquals("/logicalType", diagnostic.pointer());
        assertEquals(Set.of(USE + "/$ref", "/components/schemas/Alias/$ref", "/components/schemas/Alias2/$ref",
                        "/channels/events/messages/event/payload/$ref"),
                diagnostic.related().stream().map(SourceLocation::pointer).collect(Collectors.toSet()));
        var parsed = result.document().orElseThrow();
        var wrapper = parsed.references().resolve("/channels/events/messages/event/payload").orElseThrow();
        assertEquals("/components/schemas/Value", wrapper.pointer());
        assertEquals("event.avsc", parsed.references().resolveSchemaFile(wrapper.pointer() + "/schema").orElseThrow().file());
        assertTrue(parsed.references().resolveSchemaFile("/components/schemas/Alias/schema").isEmpty(),
                "The API requires resolving the wrapper first; it must not guess alias suffixes");
        assertEquals("#/components/schemas/Alias2", JSON.valueToTree(parsed.model())
                .at("/channels/events/messages/event/payload/$ref").asText());
    }

    @Test
    void normalizedFileSpellingsShareOneValidationResult() throws Exception {
        Files.createDirectories(root.resolve("schemas"));
        Files.writeString(root.resolve("schemas/event.avsc"), "\"Unknown\"");
        var input = document("{\"$ref\":\"schemas/event.avsc\"}");
        var other = ((ObjectNode) input.at("/components/schemas")).putObject("Other");
        other.put("schemaFormat", Avro19.Format.DEFAULT.mediaType()).putObject("schema")
                .put("$ref", "schemas/../schemas/event.avsc");
        var result = new AsyncApiParser().parse("events.json", input.toString(),
                new ParseContext(root, Set.of("schemas/event.avsc")));
        assertFalse(result.successful());
        assertEquals(1, result.diagnostics().size());
        assertEquals("schemas/event.avsc", result.diagnostics().get(0).file());
        assertEquals(2, result.diagnostics().get(0).related().size());
    }

    @Test
    void oneParserDoesNotRetainNamesOrFileSnapshotsBetweenDocuments() throws Exception {
        Files.writeString(root.resolve("event.avsc"), "{\"type\":\"record\",\"name\":\"Event\",\"fields\":[]}");
        var parser = new AsyncApiParser();
        var context = new ParseContext(root, Set.of("event.avsc"));
        var first = parser.parse("first.json", document("{\"$ref\":\"event.avsc\"}").toString(), context);
        assertTrue(first.successful(), first.diagnostics()::toString);
        Files.writeString(root.resolve("event.avsc"), "\"Event\"");
        var second = parser.parse("second.json", document("{\"$ref\":\"event.avsc\"}").toString(), context);
        assertFalse(second.successful(), "A previous file must not supply a name or a cached snapshot");
        assertEquals("avro.schema", second.diagnostics().get(0).ruleId());
        assertEquals("record", first.document().orElseThrow().references().resolveSchemaFile(USE)
                .orElseThrow().node().path("type").asText());
        assertTrue(parser.parse("inline.json", document("\"long\"").toString()).document().orElseThrow()
                .references().resolveSchemaFile(USE).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directoryAndNonUtf8TargetsProduceReferenceErrorsWithoutAPartialModel(boolean directory) throws Exception {
        Path target = root.resolve("event.avsc");
        if (directory) Files.createDirectory(target);
        else Files.write(target, new byte[]{(byte) 0xc3, 0x28});
        var result = new AsyncApiParser().parse("events.json", document("{\"$ref\":\"event.avsc\"}").toString(),
                new ParseContext(root, Set.of("event.avsc")));
        assertFalse(result.successful());
        assertTrue(result.document().isEmpty());
        assertEquals("avro.reference", result.diagnostics().get(0).ruleId());
        assertEquals("events.json", result.diagnostics().get(0).file());
        assertEquals(USE + "/$ref", result.diagnostics().get(0).pointer());
        assertFalse(result.diagnosticsJson().contains(root.toString().replace("\\", "\\\\")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\":\"array\",\"items\":[\"null\",\"string\"]}",
            "{\"type\":\"map\",\"values\":[\"null\",\"string\"]}"
    })
    void defaultUnionRuleAppliesInsideCollectionsAtTheDeclaredDefault(String type) throws Exception {
        String value = type.contains("array") ? "[\"later-branch\"]" : "{\"entry\":\"later-branch\"}";
        String schema = "{\"type\":\"record\",\"name\":\"Event\",\"fields\":[{\"name\":\"data\",\"type\":"
                + type + ",\"default\":" + value + "}]}";
        var result = new AsyncApiParser().parse("events.json", document(schema).toString());
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.ruleId().equals("avro.schema")
                && d.pointer().equals(USE + "/fields/0/default")), result.diagnostics()::toString);
    }

    @Test
    void aliasesAreReaderMetadataRatherThanExtraDeclaredTypeNames() throws Exception {
        String schema = """
                {"type":"record","name":"Outer","fields":[
                  {"name":"definition","type":{"type":"record","name":"Actual","aliases":["Alias"],"fields":[]}},
                  {"name":"use","type":"Alias"}]}
                """;
        var result = new AsyncApiParser().parse("events.json", document(schema).toString());
        assertFalse(result.successful());
        assertEquals("avro.schema", result.diagnostics().get(0).ruleId());
    }

    @Test
    void equalShortNamesInDifferentNamespacesRemainDistinct() throws Exception {
        String schema = """
                {"type":"record","name":"Container","fields":[
                  {"name":"a","type":{"type":"record","name":"left.Item","fields":[]}},
                  {"name":"b","type":{"type":"record","name":"right.Item","fields":[]}},
                  {"name":"left","type":"left.Item"},{"name":"right","type":"right.Item"}]}
                """;
        var result = new AsyncApiParser().parse("events.json", document(schema).toString());
        assertTrue(result.successful(), result.diagnostics()::toString);
    }
}
