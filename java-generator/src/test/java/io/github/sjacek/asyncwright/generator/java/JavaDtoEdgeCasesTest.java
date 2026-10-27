package io.github.sjacek.asyncwright.generator.java;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sjacek.asyncwright.api.ExtensionSpec;
import io.github.sjacek.asyncwright.parser.AsyncApiParser;
import io.github.sjacek.asyncwright.api.ExtensionExecutionException;
import io.github.sjacek.asyncwright.parser.ExtensionExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Negative profile boundaries and Java/JSON behavior, independent of application contracts. */
class JavaDtoEdgeCasesTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void preservesWireEnumsExplicitNullAndReadOnlyConstantsInCompiledModels() throws Exception {
        ObjectNode source = document("""
                {"type":"object","additionalProperties":false,"required":["count","enabled","source"],
                 "properties":{
                   "count":{"type":"integer","format":"int64","const":3000000000},
                   "enabled":{"type":"boolean","const":true},
                   "source":{"type":"string","const":"fixed.\\"source\\""},
                   "state":{"$ref":"#/components/schemas/State"},
                   "metadata":{"type":"object","additionalProperties":{"type":"string"}},
                   "nested":{"type":"object","additionalProperties":false,"properties":{"text":{"type":"string"}}},
                   "date":{"type":"string","format":"date"}}}
                """);
        schemas(source).set("State", JSON.readTree("{\"type\":[\"string\",\"null\"],\"enum\":[\"ready\",\"null\",null]}"));
        var generated = generate(source, Map.of(JavaDtoGenerator.PACKAGE_NAME, "sample.dto"));
        assertTrue(generated.successful(), generated.diagnostics()::toString);
        assertEquals(3, generated.generatedFiles().size());
        try (var loader = JavaDtoGeneratorTest.compile(directory)) {
            Class<?> event = loader.loadClass("sample.dto.Event");
            Object bean = event.getConstructor().newInstance();
            assertEquals(3_000_000_000L, event.getMethod("getCount").invoke(bean));
            assertEquals(true, event.getMethod("getEnabled").invoke(bean));
            assertEquals("fixed.\"source\"", event.getMethod("getSource").invoke(bean));
            assertThrows(NoSuchMethodException.class, () -> event.getMethod("setSource", String.class));
            assertEquals(Map.class, event.getMethod("getMetadata").getReturnType());
            assertEquals(java.time.LocalDate.class, event.getMethod("getDate").getReturnType());
            Class<?> state = loader.loadClass("sample.dto.State");
            assertEquals(List.of("READY", "NULL"), java.util.Arrays.stream(state.getEnumConstants()).map(value -> ((Enum<?>) value).name()).toList());
            Object ready = state.getMethod("fromValue", String.class).invoke(null, "ready");
            var mapper = new ObjectMapper().enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING)
                    .enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);
            assertEquals("\"ready\"", mapper.writeValueAsString(ready));
            assertSame(ready, mapper.readValue("\"ready\"", state));
            assertNull(mapper.readValue("null", state));
            assertNotNull(mapper.readValue("\"null\"", state));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"", "../outside", "a..b", "sample.class", "sample/dto", "bad;package"})
    void rejectsInvalidPackageParametersAsConfigurationFailures(String name) {
        var exception = assertThrows(ExtensionExecutionException.class,
                () -> generate(document(simple()), Map.of(JavaDtoGenerator.PACKAGE_NAME, name)));
        assertInstanceOf(IllegalArgumentException.class, exception.getCause());
    }

    @Test void rejectsUnknownAndInvalidCollectionParametersInsteadOfIgnoringTypos() throws Exception {
        ObjectNode source = document(simple());
        for (Map<String, String> options : List.of(Map.of("packagName", "sample.dto"),
                Map.of(JavaDtoGenerator.PACKAGE_NAME, "sample.dto", JavaDtoGenerator.COLLECTION_TYPE, "list"))) {
            var error = assertThrows(ExtensionExecutionException.class, () -> generate(source, options));
            assertInstanceOf(IllegalArgumentException.class, error.getCause());
        }
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"type\":[\"integer\",\"string\"]}",
            "{\"type\":\"string\",\"allOf\":[{\"type\":\"string\"}]}",
            "{\"type\":\"array\",\"items\":[{\"type\":\"string\"}]}",
            "{\"type\":\"integer\",\"format\":\"uint64\"}",
            "{\"type\":\"integer\",\"const\":2147483648}",
            "false", "true"
    })
    void rejectsUnsupportedPropertyShapesWithoutWritingPartialOutput(String schema) throws Exception {
        ObjectNode source = document(simple());
        ((ObjectNode) schemas(source).path("Event").path("properties")).set("unsupported", JSON.readTree(schema));
        var generated = generate(source, Map.of(JavaDtoGenerator.PACKAGE_NAME, "sample.dto"));
        assertFalse(generated.successful(), generated.diagnostics()::toString);
        assertTrue(generated.generatedFiles().isEmpty());
        assertEquals("java-dto.unsupported", generated.diagnostics().get(0).ruleId());
        assertTrue(generated.diagnostics().get(0).pointer().startsWith("/components/schemas/Event/properties/unsupported"));
        assertTrue(generated.diagnostics().get(0).line() > 0);
    }

    @Test void detectsEnumSymbolCollisionsAfterCaseNormalization() throws Exception {
        ObjectNode source = document(simple());
        ((ObjectNode) schemas(source).path("Event").path("properties")).set("state",
                JSON.readTree("{\"$ref\":\"#/components/schemas/State\"}"));
        schemas(source).set("State", JSON.readTree("{\"type\":\"string\",\"enum\":[\"ready\",\"READY\"]}"));
        var result = generate(source, Map.of(JavaDtoGenerator.PACKAGE_NAME, "sample.dto"));
        assertEquals("java-dto.collision", result.diagnostics().get(0).ruleId());
        assertTrue(result.generatedFiles().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"type\":\"array\",\"items\":{\"$ref\":\"#/components/schemas/Container\"}}",
            "{\"type\":\"object\",\"additionalProperties\":{\"$ref\":\"#/components/schemas/Container\"}}"
    })
    void rejectsRecursiveContainersWithoutAConcreteBeanBoundary(String container) throws Exception {
        ObjectNode source = document(simple());
        ((ObjectNode) schemas(source).path("Event").path("properties")).set("recursive",
                JSON.readTree("{\"$ref\":\"#/components/schemas/Container\"}"));
        schemas(source).set("Container", JSON.readTree(container));
        var result = generate(source, Map.of(JavaDtoGenerator.PACKAGE_NAME, "sample.dto"));
        assertFalse(result.successful(), result.diagnostics()::toString);
        assertEquals("java-dto.unsupported", result.diagnostics().get(0).ruleId());
        assertEquals("/components/schemas/Container", result.diagnostics().get(0).pointer());
        assertTrue(result.generatedFiles().isEmpty());
    }

    @Test void detectsCaseInsensitiveFileNameCollisionsBeforeOutput() throws Exception {
        ObjectNode source = document(simple());
        ObjectNode properties = (ObjectNode) schemas(source).path("Event").path("properties");
        properties.set("first", JSON.readTree("{\"$ref\":\"#/components/schemas/Other\"}"));
        properties.set("second", JSON.readTree("{\"$ref\":\"#/components/schemas/other\"}"));
        schemas(source).set("Other", JSON.readTree(simple()));
        schemas(source).set("other", JSON.readTree(simple()));
        var result = generate(source, Map.of(JavaDtoGenerator.PACKAGE_NAME, "sample.dto"));
        assertEquals("java-dto.collision", result.diagnostics().get(0).ruleId());
        assertTrue(result.generatedFiles().isEmpty());
    }

    @Test void detectsReservedAdditionalPropertyMapNameButAllowsItOnClosedObjects() throws Exception {
        ObjectNode source = document("{\"type\":\"object\",\"properties\":{\"additionalProperties\":{\"type\":\"string\"}}}");
        var invalid = generate(source, Map.of(JavaDtoGenerator.PACKAGE_NAME, "sample.dto"));
        assertEquals("java-dto.collision", invalid.diagnostics().get(0).ruleId());
        ((ObjectNode) schemas(source).path("Event")).put("additionalProperties", false);
        var valid = generate(source, Map.of(JavaDtoGenerator.PACKAGE_NAME, "sample.dto"));
        assertTrue(valid.successful(), valid.diagnostics()::toString);
        try (var loader = JavaDtoGeneratorTest.compile(directory)) {
            assertEquals(String.class, loader.loadClass("sample.dto.Event").getMethod("getAdditionalProperties").getReturnType());
        }
    }

    @Test void appliesCollectionChoiceWithoutWeakeningUniqueItems() throws Exception {
        ObjectNode source = document("""
                {"type":"object","additionalProperties":false,"properties":{
                  "values":{"type":"array","items":{"type":"integer"}},
                  "unique":{"type":"array","uniqueItems":true,"items":{"type":"string"}}}}
                """);
        var result = generate(source, Map.of(JavaDtoGenerator.PACKAGE_NAME, "sample.dto", JavaDtoGenerator.COLLECTION_TYPE, "Set"));
        assertTrue(result.successful(), result.diagnostics()::toString);
        try (var loader = JavaDtoGeneratorTest.compile(directory)) {
            Class<?> type = loader.loadClass("sample.dto.Event");
            assertEquals(java.util.Set.class, type.getMethod("getValues").getReturnType());
            assertEquals(java.util.Set.class, type.getMethod("getUnique").getReturnType());
        }
        assertFalse(Files.readString(directory.resolve("sample/dto/Event.java")).endsWith("\n"),
                "The source compatibility profile intentionally preserves the legacy terminal byte");
    }

    private static String simple() {
        return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"value\":{\"type\":\"string\"}}}";
    }
    private static ObjectNode schemas(ObjectNode document) {
        return (ObjectNode) document.path("components").path("schemas");
    }
    private static ObjectNode document(String schema) throws Exception {
        ObjectNode root = (ObjectNode) JSON.readTree("""
                {"asyncapi":"3.0.0","info":{"title":"DTO boundaries","version":"1"},
                 "components":{"messages":{"event":{"payload":{"$ref":"#/components/schemas/Event"}}},"schemas":{}}}
                """);
        schemas(root).set("Event", JSON.readTree(schema));
        return root;
    }
    private io.github.sjacek.asyncwright.api.ExtensionResult generate(ObjectNode document, Map<String, String> options) throws Exception {
        var parsed = new AsyncApiParser().parse("document.json", document.toString());
        assertTrue(parsed.successful(), parsed.diagnostics()::toString);
        return new ExtensionExecutor().generate(parsed, List.of(),
                List.of(new ExtensionSpec(JavaDtoGenerator.class.getName(), options)), directory);
    }
}
