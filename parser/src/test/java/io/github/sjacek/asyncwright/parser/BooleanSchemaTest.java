package io.github.sjacek.asyncwright.parser;

import com.asyncapi.schemas.asyncapi.AsyncAPISchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.github.sjacek.asyncwright.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BooleanSchemaTest {
    private final AsyncApiParser parser = new AsyncApiParser();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ParseResult parse(String body) {
        return parser.parse("specs/events.json", "{\"asyncapi\":\"3.0.0\",\"info\":{\"title\":\"Boolean schemas\",\"version\":\"1.0.0\"}," + body + "}");
    }

    private static AsyncApiDocument accepted(ParseResult result) throws Exception {
        assertTrue(result.successful(), result.diagnosticsJson());
        return result.document().orElseThrow();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void componentsPayloadsAndHeadersRetainTheBooleanValue(boolean value) throws Exception {
        var document = accepted(parse("\"components\":{\"schemas\":{\"Value\":" + value
                + "},\"messages\":{\"m\":{\"payload\":" + value + ",\"headers\":" + value + "}}}"));
        JsonNode model = MAPPER.valueToTree(document.model());
        for (String pointer : List.of("/components/schemas/Value", "/components/messages/m/payload", "/components/messages/m/headers")) {
            assertTrue(model.at(pointer).isBoolean(), pointer);
            assertEquals(value, model.at(pointer).booleanValue(), pointer);
            assertEquals(document.source().at(pointer), model.at(pointer));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"properties", "patternProperties", "definitions", "dependencies", "items", "additionalItems",
            "additionalProperties", "contains", "propertyNames", "if", "then", "else", "not", "allOf", "anyOf", "oneOf", "tuple"})
    void nestedSchemaPositionsPreserveBothBooleanLeaves(String keyword) throws Exception {
        boolean map = List.of("properties", "patternProperties", "definitions", "dependencies").contains(keyword);
        boolean array = List.of("allOf", "anyOf", "oneOf", "tuple").contains(keyword);
        for (boolean value : List.of(true, false)) {
            String property = keyword.equals("tuple") ? "items" : keyword;
            String node = map ? "{\"flag\":" + value + "}" : array ? "[" + value + "]" : Boolean.toString(value);
            var document = accepted(parse("\"components\":{\"schemas\":{\"Value\":{\"" + property + "\":" + node + "}}}"));
            String pointer = "/components/schemas/Value/" + property + (map ? "/flag" : array ? "/0" : "");
            JsonNode model = MAPPER.valueToTree(document.model());
            assertEquals(document.source().at(pointer), model.at(pointer), keyword);
            var target = document.references().resolve(pointer).orElseThrow();
            assertEquals(value, target.node().booleanValue());
            AsyncAPISchema typed = document.references().resolve(pointer, AsyncAPISchema.class).orElseThrow();
            assertEquals(target.node(), MAPPER.valueToTree(typed));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void implicitAndExplicitDefaultFormatWrappersPreserveBooleanSchemas(boolean value) throws Exception {
        for (String format : List.of("", "application/vnd.aai.asyncapi;version=3.0.0",
                "application/vnd.aai.asyncapi+json;version=3.0.0", "application/vnd.aai.asyncapi+yaml;version=3.0.0")) {
            String wrapper = "{" + (format.isEmpty() ? "" : "\"schemaFormat\":\"" + format + "\",") + "\"schema\":" + value + "}";
            var document = accepted(parse("\"components\":{\"schemas\":{\"Value\":" + wrapper
                    + "},\"messages\":{\"m\":{\"payload\":" + wrapper + "}}}"));
            JsonNode model = MAPPER.valueToTree(document.model());
            assertEquals(value, model.at("/components/schemas/Value/schema").booleanValue());
            assertTrue(model.at("/components/schemas/Value/schema").isBoolean());
            assertEquals(value, model.at("/components/messages/m/payload/schema").booleanValue());
            assertTrue(model.at("/components/messages/m/payload/schema").isBoolean());
            if (format.isEmpty()) {
                assertFalse(document.source().at("/components/schemas/Value").has("schemaFormat"));
                assertEquals("application/vnd.aai.asyncapi+json;version=3.0.0",
                        model.at("/components/schemas/Value/schemaFormat").asText());
            }
        }
    }

    @Test
    void referenceAliasesCanResolveFalseWithoutTreatingItAsMissing() throws Exception {
        var document = accepted(parse("""
                "components":{"schemas":{"None":false,"Alias":{"$ref":"#/components/schemas/None"}},
                  "messages":{"m":{"payload":{"$ref":"#/components/schemas/Alias"}}}}
                """));
        var target = document.references().resolve("/components/messages/m/payload").orElseThrow();
        assertEquals("/components/schemas/None", target.pointer());
        assertTrue(target.node().isBoolean());
        assertFalse(target.node().booleanValue());
        assertTrue(MAPPER.valueToTree(document.model()).at("/components/messages/m/payload").has("$ref"));
        assertEquals(2, document.references().related("/components/schemas/None").size());
    }

    @Test
    void booleanDependencyIsAnIndexedSchemaTarget() throws Exception {
        var document = accepted(parse("""
                "components":{"schemas":{"Value":{"dependencies":{"flag":false}},
                  "Alias":{"$ref":"#/components/schemas/Value/dependencies/flag"}}}
                """));
        var leaf = document.references().resolve("/components/schemas/Alias", BooleanSchema.class).orElseThrow();
        assertFalse(leaf.value());
    }

    @Test
    void aBooleanInstanceValueCannotBeUsedAsADocumentSchemaTarget() {
        var result = parse("""
                "x-data":{"value":false},"components":{"schemas":{"Alias":{"$ref":"#/x-data/value"}}}
                """);
        assertFalse(result.successful());
        assertTrue(result.document().isEmpty());
        assertEquals("reference.type", result.diagnostics().get(0).ruleId());
        assertEquals("/components/schemas/Alias/$ref", result.diagnostics().get(0).pointer());
    }

    @Test
    void yamlBooleansRetainValuesAndTheirOriginalPositions() throws Exception {
        var document = accepted(parser.parse("specs/events.yaml", """
                asyncapi: 3.0.0
                info: {title: Boolean schemas, version: 1.0.0}
                components:
                  schemas:
                    Any: true
                    None: false
                """));
        var none = document.references().resolve("/components/schemas/None", BooleanSchema.class).orElseThrow();
        assertFalse(none.value());
        assertEquals(6, document.location("/components/schemas/None").line());
        assertEquals(5, document.location("/components/schemas/None").column());
    }

    @Test
    void invalidObjectSchemasStillReportTheirSourcePathAndRelatedUses() {
        var result = parse("""
                "components":{"schemas":{"Value":{"allOf":[true,{"type":17}]}},
                  "messages":{"m":{"payload":{"$ref":"#/components/schemas/Value"}}}}
                """);
        assertFalse(result.successful());
        assertTrue(result.document().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.pointer().equals("/components/schemas/Value/allOf/1/type")
                && d.related().size() == 1), result.diagnostics()::toString);
    }

    @Test
    void booleanValuesOutsideSchemaPositionsDoNotBecomeValidObjects() {
        for (String body : List.of("\"channels\":{\"events\":false}", "\"operations\":{\"send\":true}",
                "\"components\":{\"messages\":{\"m\":false}}", "\"components\":{\"securitySchemes\":{\"s\":true}}")) {
            var result = parse(body);
            assertFalse(result.successful(), body);
            assertTrue(result.document().isEmpty());
            assertTrue(result.diagnostics().stream().allMatch(d -> d.ruleId().startsWith("schema.")), result.diagnostics()::toString);
        }
    }

    @Test
    void invalidScalarSchemasRemainInvalid() {
        for (String value : List.of("null", "42", "\"false\"", "[]")) {
            var result = parse("\"components\":{\"schemas\":{\"Value\":" + value + "}}");
            assertFalse(result.successful(), value);
            assertTrue(result.document().isEmpty());
        }
    }

    @Test
    void schemaBooleanAndInstanceBooleanStayDistinctInExtensionsAndGeneratedOutput(@TempDir Path directory) throws Exception {
        var result = parse("""
                "components":{"schemas":{"None":false,"Value":{"const":false,"default":false,"examples":[false]}}}
                """);
        accepted(result);
        var executed = new ExtensionExecutor().generate(result, List.of(new ExtensionSpec(BooleanRule.class.getName(), Map.of())),
                List.of(new ExtensionSpec(BooleanGenerator.class.getName(), Map.of())), directory);
        assertTrue(executed.successful());
        assertEquals("false", Files.readString(directory.resolve("schema.json")));
    }

    @Test
    void booleanLeafHasAnExplicitTypedValueAndRemainsDistinctFromAnEmptyObjectSchema() throws Exception {
        var document = accepted(parse("\"components\":{\"schemas\":{\"Value\":false}}"));
        var leaf = document.references().resolve("/components/schemas/Value", BooleanSchema.class).orElseThrow();
        assertFalse(leaf.value());
        assertEquals(new BooleanSchema(false), leaf);
        assertEquals(new BooleanSchema(false).hashCode(), leaf.hashCode());
        assertNotEquals(new BooleanSchema(true), leaf);
        AsyncAPISchema empty = new AsyncAPISchema();
        assertNotEquals(empty, leaf);
        assertNotEquals(leaf, empty);
        leaf.setType("object");
        assertEquals("false", MAPPER.writeValueAsString(leaf));
        assertEquals("false", MAPPER.writeValueAsString(document.references().resolve("/components/schemas/Value", AsyncAPISchema.class).orElseThrow()));
    }

    @Test
    void serializedTrueAndFalseHaveTheExpectedJsonSchemaMeaning() throws Exception {
        var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7);
        for (boolean value : List.of(true, false)) {
            var schema = registry.getSchema(MAPPER.writeValueAsString(new BooleanSchema(value)));
            for (String instance : List.of("null", "false", "42", "\"text\"", "[]", "{}"))
                assertEquals(value, schema.validate(instance, InputFormat.JSON).isEmpty(), instance);
        }
    }

    @Test
    void runtimeSchemaCorrectionOnlyChangesTheObjectConditionAndDoesNotMutateUpstreamBytes() throws Exception {
        JsonNode corrected = SchemaCompatibility.schema();
        ObjectNode condition = (ObjectNode) corrected.path("definitions")
                .path("http://asyncapi.com/definitions/3.0.0/anySchema.json").path("if");
        assertEquals("object", condition.path("type").asText());
        condition.remove("type");
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/schemas/asyncapi/3.0.0.json"))) {
            assertEquals(MAPPER.readTree(input), corrected);
        }
        assertEquals("object", SchemaCompatibility.schema().path("definitions")
                .path("http://asyncapi.com/definitions/3.0.0/anySchema.json").path("if").path("type").asText());
    }

    public static class BooleanRule implements AsyncApiRule {
        @Override public List<Diagnostic> validate(ExtensionContext context) {
            JsonNode model = MAPPER.valueToTree(context.document().model());
            assertEquals(context.document().source().at("/components/schemas/None"), model.at("/components/schemas/None"));
            var components = Objects.requireNonNull(context.document().model().getComponents());
            var schemas = Objects.requireNonNull(components.getSchemas());
            AsyncAPISchema schema = assertInstanceOf(AsyncAPISchema.class, schemas.get("Value"));
            assertEquals(Boolean.FALSE, schema.getConstValue());
            assertEquals(Boolean.FALSE, schema.getDefaultValue());
            assertEquals(List.of(false), schema.getExamples());
            return List.of();
        }
    }

    public static class BooleanGenerator implements AsyncApiGenerator {
        @Override public List<Diagnostic> generate(GeneratorContext context) throws Exception {
            context.output().writeUtf8("schema.json", MAPPER.writeValueAsString(
                    context.document().references().resolve("/components/schemas/None", AsyncAPISchema.class).orElseThrow()));
            return List.of();
        }
    }
}
