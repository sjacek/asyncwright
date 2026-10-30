package io.github.sjacek.asyncwright.generator.java;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sjacek.asyncwright.api.ExtensionExecutionException;
import io.github.sjacek.asyncwright.api.ExtensionResult;
import io.github.sjacek.asyncwright.api.ExtensionSpec;
import io.github.sjacek.asyncwright.parser.AsyncApiParser;
import io.github.sjacek.asyncwright.parser.ExtensionExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Compiled public API checks for schema boundaries, output portability and host failure semantics. */
class JavaDtoCompatibilityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PACKAGE = "sample.dto";
    @TempDir
    Path output;

    @ParameterizedTest(name = "{0}/{1}: required, optional, nullable and generic positions")
    @MethodSource("scalarTypes")
    void boxesOnlyWhereRequiredByNullabilityOrJavaGenerics(String type, String format,
                                                         Class<?> primitive, Class<?> boxed) throws Exception {
        ObjectNode scalar = JSON.createObjectNode().put("type", type);
        if (!format.isEmpty()) scalar.put("format", format);
        ObjectNode nullable = scalar.deepCopy();
        nullable.putArray("type").add(type).add("null");
        ObjectNode schema = bean();
        schema.putArray("required").add("requiredValue").add("nullableValue");
        ObjectNode properties = schema.withObject("/properties");
        properties.set("requiredValue", scalar);
        properties.set("optionalValue", scalar);
        properties.set("nullableValue", nullable);
        properties.set("nullableOptional", nullable);
        properties.putObject("values").put("type", "array").set("items", scalar);
        properties.putObject("dictionary").put("type", "object").set("additionalProperties", scalar);

        assertGenerated(document(schema));
        try (var loader = JavaDtoGeneratorTest.compile(output)) {
            Class<?> event = loader.loadClass(PACKAGE + ".Event");
            assertEquals(primitive, event.getMethod("getRequiredValue").getReturnType());
            for (String name : List.of("OptionalValue", "NullableValue", "NullableOptional"))
                assertEquals(boxed, event.getMethod("get" + name).getReturnType());
            ParameterizedType list = (ParameterizedType) event.getMethod("getValues").getGenericReturnType();
            assertEquals(List.class, list.getRawType());
            assertArrayEquals(new Object[] {boxed}, list.getActualTypeArguments());
            ParameterizedType map = (ParameterizedType) event.getMethod("getDictionary").getGenericReturnType();
            assertEquals(Map.class, map.getRawType());
            assertArrayEquals(new Object[] {String.class, boxed}, map.getActualTypeArguments());
        }
    }

    static Stream<Arguments> scalarTypes() {
        return Stream.of(
                Arguments.of("integer", "", int.class, Integer.class),
                Arguments.of("integer", "int32", int.class, Integer.class),
                Arguments.of("integer", "int64", long.class, Long.class),
                Arguments.of("number", "", double.class, Double.class),
                Arguments.of("boolean", "", boolean.class, Boolean.class));
    }

    @Test
    void compilesConcreteRecursionThroughListsAndTypedAdditionalProperties() throws Exception {
        ObjectNode root = document(JSON.readTree("""
                {"type":"object","additionalProperties":false,"properties":{
                  "root":{"$ref":"#/components/schemas/Node"},
                  "createdAt":{"type":"string","format":"date-time"}}}
                """));
        root.withObject("/components/schemas").set("Node", JSON.readTree("""
                {"type":"object","properties":{
                  "children":{"type":"array","items":{"$ref":"#/components/schemas/Node"}}},
                 "additionalProperties":{"$ref":"#/components/schemas/Node"}}
                """));
        assertGenerated(root);
        try (var loader = JavaDtoGeneratorTest.compile(output)) {
            Class<?> event = loader.loadClass(PACKAGE + ".Event");
            Class<?> node = loader.loadClass(PACKAGE + ".Node");
            assertEquals(java.time.OffsetDateTime.class, event.getMethod("getCreatedAt").getReturnType());
            ParameterizedType list = (ParameterizedType) node.getMethod("getChildren").getGenericReturnType();
            assertArrayEquals(new Object[] {node}, list.getActualTypeArguments());
            ParameterizedType extras = (ParameterizedType) node.getMethod("getAdditionalProperties").getGenericReturnType();
            assertArrayEquals(new Object[] {String.class, node}, extras.getActualTypeArguments());
            assertThrows(NoSuchMethodException.class, () -> event.getMethod("getAdditionalProperties"));
        }
    }

    @Test
    void findsInlineChannelMessagesAndDeduplicatesAliasesWithoutGeneratingHeaders() throws Exception {
        ObjectNode root = (ObjectNode) JSON.readTree("""
                {"asyncapi":"3.0.0","info":{"title":"Message roots","version":"1"},
                 "channels":{"a/b~c":{"$ref":"#/components/channels/shared"},
                             "second":{"$ref":"#/components/channels/shared"}},
                 "components":{
                   "messages":{
                     "event":{"payload":{"$ref":"#/components/schemas/Event"},
                              "headers":{"$ref":"#/components/schemas/HeaderOnly"}},
                     "alias":{"$ref":"#/components/messages/event"},
                     "headersOnly":{"headers":{"$ref":"#/components/schemas/HeaderOnly"}}},
                   "channels":{"shared":{"address":"events","messages":{
                     "event":{"$ref":"#/components/messages/alias"},
                     "Changed":{"payload":{"type":"object","additionalProperties":false,
                                           "properties":{"value":{"type":"string"}}}}}}},
                   "schemas":{
                     "Event":{"type":"object","additionalProperties":false,"properties":{"id":{"type":"string"}}},
                     "HeaderOnly":{"type":"object","properties":{"trace":{"type":"string"}}},
                     "Unused":{"type":"object","properties":{"unused":{"type":"string"}}}}}}
                """);
        var result = generate(root);
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertEquals(List.of("sample/dto/ChangedPayload.java", "sample/dto/Event.java"),
                result.generatedFiles().stream().sorted().toList());
        try (var loader = JavaDtoGeneratorTest.compile(output)) {
            assertEquals(String.class, loader.loadClass(PACKAGE + ".ChangedPayload").getMethod("getValue").getReturnType());
        }
    }

    @Test
    void preservesUnicodeAndEscapedConstantValuesInCompiledJava() throws Exception {
        String literal = "zażółć \"quoted\" \\u000a\n\r\t\b\f" + (char) 1 + "7" + (char) 127;
        ObjectNode schema = bean();
        ObjectNode properties = schema.withObject("/properties");
        properties.putObject("tekst").put("type", "string").put("const", literal);
        ObjectNode nullable = properties.putObject("nullableConstant");
        nullable.putArray("type").add("string").add("null");
        nullable.putNull("const");
        assertGenerated(document(schema));
        try (var loader = JavaDtoGeneratorTest.compile(output)) {
            Class<?> event = loader.loadClass(PACKAGE + ".Event");
            Object instance = event.getConstructor().newInstance();
            assertEquals(literal, event.getMethod("getTekst").invoke(instance));
            assertNull(event.getMethod("getNullableConstant").invoke(instance));
            assertThrows(NoSuchMethodException.class, () -> event.getMethod("setTekst", String.class));
            assertThrows(NoSuchMethodException.class, () -> event.getMethod("setNullableConstant", String.class));
        }
        byte[] bytes = Files.readAllBytes(output.resolve("sample/dto/Event.java"));
        assertEquals(-1, new String(bytes, StandardCharsets.UTF_8).indexOf('\r'));
        assertFalse(new String(bytes, StandardCharsets.UTF_8).startsWith("\uFEFF"));
    }

    @ParameterizedTest(name = "constant {1}")
    @MethodSource("integerConstants")
    void preservesFullIntegerConstantRange(String format, Number value, Class<?> type) throws Exception {
        ObjectNode schema = bean();
        schema.putArray("required").add("value");
        ObjectNode constant = schema.withObject("/properties").putObject("value").put("type", "integer");
        if (!format.isEmpty()) constant.put("format", format);
        constant.set("const", JSON.valueToTree(value));
        assertGenerated(document(schema));
        try (var loader = JavaDtoGeneratorTest.compile(output)) {
            Class<?> event = loader.loadClass(PACKAGE + ".Event");
            assertEquals(type, event.getMethod("getValue").getReturnType());
            assertEquals(value, event.getMethod("getValue").invoke(event.getConstructor().newInstance()));
        }
    }

    static Stream<Arguments> integerConstants() {
        return Stream.of(Arguments.of("", Integer.MIN_VALUE, int.class),
                Arguments.of("int32", Integer.MAX_VALUE, int.class),
                Arguments.of("int64", Long.MIN_VALUE, long.class),
                Arguments.of("int64", Long.MAX_VALUE, long.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"String", "Map", "record", "var", "1Event", "Bad-Name"})
    void rejectsUnrepresentableNamedTypesBeforeOutput(String name) throws Exception {
        ObjectNode root = document(bean());
        root.withObject("/components/schemas").set(name, root.at("/components/schemas/Event").deepCopy());
        root.withObject("/components/messages/event/payload").put("$ref", "#/components/schemas/" + name);
        var result = generate(root);
        assertFalse(result.successful(), result.diagnostics()::toString);
        assertEquals("java-dto.name", result.diagnostics().get(0).ruleId());
        assertEquals("/components/schemas/" + name, result.diagnostics().get(0).pointer());
        assertTrue(result.generatedFiles().isEmpty());
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    void usesLocaleIndependentEnumSymbols() throws Exception {
        ObjectNode root = document(bean());
        root.withObject("/components/schemas/Event/properties").putObject("state")
                .put("$ref", "#/components/schemas/State");
        root.withObject("/components/schemas").set("State", JSON.readTree("""
                {"type":"string","enum":["id","invalid"]}
                """));
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertGenerated(root);
            try (var loader = JavaDtoGeneratorTest.compile(output)) {
                Class<?> state = loader.loadClass(PACKAGE + ".State");
                assertEquals(List.of("ID", "INVALID"),
                        java.util.Arrays.stream(state.getEnumConstants()).map(value -> ((Enum<?>) value).name()).toList());
                assertEquals("id", state.getMethod("fromValue", String.class).invoke(null, "id").toString());
            }
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void preservesFilesystemFailuresAsInfrastructureErrors() throws Exception {
        // A deterministic obstruction works on Windows too, without relying on chmod/ACL behavior.
        Files.writeString(output.resolve("sample"), "ordinary file, not a package directory");
        var error = assertThrows(ExtensionExecutionException.class, () -> generate(document(bean())));
        assertEquals(ExtensionExecutionException.Kind.GENERATOR, error.kind());
        assertEquals(JavaDtoGenerator.class.getName(), error.extensionClass());
        assertInstanceOf(IOException.class, error.getCause());
    }

    private static ObjectNode bean() {
        ObjectNode schema = JSON.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putObject("properties");
        return schema;
    }

    private static ObjectNode document(com.fasterxml.jackson.databind.JsonNode schema) throws Exception {
        ObjectNode root = (ObjectNode) JSON.readTree("""
                {"asyncapi":"3.0.0","info":{"title":"Java compatibility","version":"1"},
                 "components":{"messages":{"event":{"payload":{"$ref":"#/components/schemas/Event"}}},
                               "schemas":{}}}
                """);
        root.withObject("/components/schemas").set("Event", schema);
        return root;
    }

    private void assertGenerated(ObjectNode source) throws Exception {
        var result = generate(source);
        assertTrue(result.successful(), result.diagnostics()::toString);
    }

    private ExtensionResult generate(ObjectNode source) throws Exception {
        // Escape DEL for the shared YAML/JSON scanner rather than inserting a raw YAML control.
        String input = source.toString().replace(String.valueOf((char) 127), "\\u007f");
        var parsed = new AsyncApiParser().parse("compatibility.json", input);
        assertTrue(parsed.successful(), parsed.diagnostics()::toString);
        return new ExtensionExecutor().generate(parsed, List.of(),
                List.of(new ExtensionSpec(JavaDtoGenerator.class.getName(),
                        Map.of(JavaDtoGenerator.PACKAGE_NAME, PACKAGE))), output);
    }
}
