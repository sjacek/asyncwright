package io.github.sjacek.asyncwright.parser;

import com.asyncapi.schemas.asyncapi.AsyncAPISchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sjacek.asyncwright.api.AsyncApi30;
import io.github.sjacek.asyncwright.api.BuiltinRuleId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Every protocol/owner/field combination is checked through the public parser and resolver. */
class BindingSchemaPositionsTest {
    private static final ObjectMapper MAPPER = ModelMapping.mapper();
    private static final String SCHEMA_REF = "http://asyncapi.com/definitions/3.0.0/schema.json";

    static Stream<BindingSchemaPositions.Position> positions() { return BindingSchemaPositions.ALL.stream(); }

    static String pointer(BindingSchemaPositions.Position position) {
        return "/components/" + collection(position) + "/Shared/" + position.protocol() + "/" + position.field();
    }

    private static String collection(BindingSchemaPositions.Position position) {
        return switch (position.owner()) {
            case SERVER_BINDINGS -> "serverBindings";
            case CHANNEL_BINDINGS -> "channelBindings";
            case OPERATION_BINDINGS -> "operationBindings";
            case MESSAGE_BINDINGS -> "messageBindings";
            default -> throw new IllegalArgumentException("Not a binding owner");
        };
    }

    static ObjectNode fixture(BindingSchemaPositions.Position position, JsonNode value) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("asyncapi", "3.0.0");
        root.putObject("info").put("title", "Binding schemas").put("version", "1.0.0");
        ObjectNode components = root.putObject("components");
        components.putObject("schemas").putObject("Value").put("type", "object").putObject("properties")
                .putObject("id").put("type", "string");
        ((ObjectNode) components.path("schemas")).putObject("Alias").put("$ref", "#/components/schemas/Value");
        ObjectNode maps = components.putObject(collection(position));
        maps.putObject("Alias").put("$ref", "#/components/" + collection(position) + "/Shared");
        ObjectNode binding = maps.putObject("Shared").putObject(position.protocol());
        binding.put("bindingVersion", switch (position.protocol()) {
            case "kafka" -> "0.5.0";
            case "mqtt" -> "0.2.0";
            case "http" -> "0.3.0";
            case "ws" -> "0.1.0";
            default -> "0.0.1";
        });
        if (position.protocol().equals("http") && position.owner() == DocumentIndex.Kind.OPERATION_BINDINGS)
            binding.put("method", "GET");
        binding.set(position.field(), value);
        return root;
    }

    private static ParseResult parse(JsonNode root) { return new AsyncApiParser().parse("bindings.json", root.toPrettyString()); }
    private static ObjectNode ref(String target) { return MAPPER.createObjectNode().put("$ref", target); }

    @ParameterizedTest @MethodSource("positions")
    void keepsReferencesInTheModelAndResolvesAliasesIndependently(BindingSchemaPositions.Position position) throws Exception {
        ObjectNode root = fixture(position, ref("#/components/schemas/Alias"));
        var result = parse(root);
        assertTrue(result.successful(), result.diagnostics()::toString);
        var document = result.document().orElseThrow();
        assertEquals(root, document.source());
        JsonNode mappedReference = MAPPER.valueToTree(document.model()).at(pointer(position));
        assertEquals(root.at(pointer(position)).path("$ref"), mappedReference.path("$ref"));
        assertTrue(mappedReference.path("type").isNull(), "The model reference must not expand into the target");
        var target = document.references().resolve(pointer(position)).orElseThrow();
        assertEquals("/components/schemas/Value", target.pointer());
        JsonNode typed = MAPPER.valueToTree(document.references()
                .resolve(pointer(position), AsyncAPISchema.class).orElseThrow());
        assertEquals(target.node().path("type"), typed.path("type"));
        assertEquals(target.node().at("/properties/id/type"), typed.at("/properties/id/type"));
        assertTrue(document.references().related("/components/schemas/Value").stream()
                .anyMatch(location -> location.pointer().equals(pointer(position) + "/$ref")));
    }

    @ParameterizedTest @MethodSource("positions")
    void inlineAndAliasedBindingMapsShareTheSameSchemaRules(BindingSchemaPositions.Position position) {
        for (boolean alias : new boolean[]{false, true}) {
            ObjectNode root = fixture(position, ref("#/components/schemas/Value"));
            ObjectNode owner = switch (position.owner()) {
                case SERVER_BINDINGS -> root.putObject("servers").putObject("broker")
                        .put("host", "localhost").put("protocol", position.protocol());
                case CHANNEL_BINDINGS -> root.putObject("channels").putObject("events");
                case MESSAGE_BINDINGS -> root.putObject("channels").putObject("events")
                        .putObject("messages").putObject("Event");
                case OPERATION_BINDINGS -> {
                    root.putObject("channels").putObject("events");
                    var operation = root.putObject("operations").putObject("send").put("action", "send");
                    operation.putObject("channel").put("$ref", "#/channels/events");
                    yield operation;
                }
                default -> throw new AssertionError(position);
            };
            owner.set("bindings", alias ? ref("#/components/" + collection(position) + "/Alias")
                    : root.at("/components/" + collection(position) + "/Shared").deepCopy());
            var result = parse(root);
            assertTrue(result.successful(), result.diagnostics()::toString);
            ((ObjectNode) root.at("/components/schemas")).set("Value",
                    ref("#/components/" + collection(position) + "/Alias"));
            var rejected = parse(root);
            assertTrue(rejected.document().isEmpty());
            assertTrue(rejected.diagnostics().stream().anyMatch(d -> d.ruleId().equals(BuiltinRuleId.REFERENCE_TYPE.id())),
                    rejected.diagnostics()::toString);
        }
    }

    @ParameterizedTest
    @CsvSource({"server,sessionExpiryInterval,-1", "server,maximumPacketSize,0",
            "server,maximumPacketSize,4294967296", "operation,messageExpiryInterval,-1",
            "operation,messageExpiryInterval,4294967296", "message,correlationData,123",
            "message,responseTopic,\"\"", "message,responseTopic,[]"})
    void correctedMqttAlternativesStillRejectInvalidScalars(String owner, String field, String json) throws Exception {
        var kind = switch (owner) {
            case "server" -> DocumentIndex.Kind.SERVER_BINDINGS;
            case "operation" -> DocumentIndex.Kind.OPERATION_BINDINGS;
            default -> DocumentIndex.Kind.MESSAGE_BINDINGS;
        };
        var result = parse(fixture(new BindingSchemaPositions.Position("mqtt", kind, field), MAPPER.readTree(json)));
        assertTrue(result.document().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.ruleId().startsWith("schema.")), result.diagnostics()::toString);
    }

    @ParameterizedTest @MethodSource("positions")
    void rejectsWrongKindsMissingTargetsAndExternalReferences(BindingSchemaPositions.Position position) {
        for (String target : new String[]{"#/components/servers/Broker", "#/components/schemas/Missing", "key.json", "#/Bad~2"}) {
            ObjectNode root = fixture(position, ref(target));
            ((ObjectNode) root.path("components")).putObject("servers").putObject("Broker")
                    .put("host", "localhost").put("protocol", "kafka");
            var result = parse(root);
            assertTrue(result.document().isEmpty());
            BuiltinRuleId expected = target.contains("Broker") ? BuiltinRuleId.REFERENCE_TYPE
                    : target.contains("Missing") ? BuiltinRuleId.REFERENCE_MISSING
                    : target.startsWith("#") ? BuiltinRuleId.REFERENCE_POINTER : BuiltinRuleId.UNSUPPORTED_REFERENCE;
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.ruleId().equals(expected.id())
                    && d.pointer().equals(pointer(position) + "/$ref") && d.line() > 1), result.diagnostics()::toString);
        }
    }

    @ParameterizedTest @MethodSource("positions")
    void permitsConcreteRecursionButRejectsCyclesOfAliases(BindingSchemaPositions.Position position) {
        ObjectNode recursive = MAPPER.createObjectNode().put("type", "object");
        recursive.putObject("properties").set("next", ref("#" + pointer(position)));
        var accepted = parse(fixture(position, recursive));
        assertTrue(accepted.successful(), accepted.diagnostics()::toString);
        ObjectNode cyclic = fixture(position, ref("#/components/schemas/Alias"));
        ((ObjectNode) cyclic.at("/components/schemas")).set("Value", ref("#" + pointer(position)));
        var rejected = parse(cyclic);
        assertTrue(rejected.document().isEmpty());
        assertTrue(rejected.diagnostics().stream().anyMatch(d -> d.ruleId().equals(BuiltinRuleId.REFERENCE_CYCLE.id())),
                rejected.diagnostics()::toString);
    }

    @ParameterizedTest @MethodSource("positions")
    void literalsInsideSchemasAreDataRatherThanReferenceEdges(BindingSchemaPositions.Position position) {
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object");
        schema.set("default", ref("not-a-reference.json"));
        schema.putArray("examples").add(ref("#/Missing"));
        schema.putObject("properties").putObject("child").put("type", "string");
        var result = parse(fixture(position, schema));
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertEquals(schema, result.document().orElseThrow().source().at(pointer(position)));
    }

    /** URI fragment decoding and JSON Pointer escaping remain separate from component-name validation. */
    @ParameterizedTest @MethodSource("positions")
    void encodedReferencesReachNestedSchemaPropertiesAndResolverViewsAreIndependent(
            BindingSchemaPositions.Position position) {
        String uri = "#/components/schemas/Value/properties/a~1b~0%20c+d";
        ObjectNode root = fixture(position, ref(uri));
        ((ObjectNode) root.at("/components/schemas/Value/properties"))
                .putObject("a/b~ c+d").put("type", "string");
        var parsed = parse(root);
        assertTrue(parsed.successful(), parsed.diagnostics()::toString);
        var document = parsed.document().orElseThrow();
        var target = document.references().resolve(pointer(position)).orElseThrow();
        assertEquals("/components/schemas/Value/properties/a~1b~0 c+d", target.pointer());
        assertEquals("string", target.node().path("type").textValue());
        assertEquals(uri, MAPPER.valueToTree(document.model()).at(pointer(position) + "/$ref").textValue());
        // Mutating a caller's resolver copy cannot change source, model or the next resolver result.
        ((ObjectNode) target.node()).put("type", "number");
        assertEquals("string", document.references().resolve(pointer(position)).orElseThrow().node().path("type").textValue());
        assertEquals(root, document.source());
    }

    /** Capability checks must also run in protocol schemas instead of falling through to model.mapping. */
    @ParameterizedTest @MethodSource("positions")
    void protocolSchemasKeepTheSameDialectAndResourceBoundariesAsPayloadSchemas(
            BindingSchemaPositions.Position position) {
        for (String keyword : List.of("$schema", "$id", "schemaFormat")) {
            ObjectNode schema = MAPPER.createObjectNode();
            BuiltinRuleId expected;
            if (keyword.equals("$schema")) {
                schema.put(keyword, "https://json-schema.org/draft/2020-12/schema");
                expected = BuiltinRuleId.UNSUPPORTED_SCHEMA_DIALECT;
            } else if (keyword.equals("$id")) {
                schema.put(keyword, "urn:example:key");
                expected = BuiltinRuleId.UNSUPPORTED_SCHEMA_ID;
            } else {
                schema.put(keyword, "application/vnd.apache.avro;version=1.9.0");
                schema.putObject("schema").put("type", "string");
                expected = BuiltinRuleId.UNSUPPORTED_CONSTRUCT;
            }
            var parsed = parse(fixture(position, schema));
            assertTrue(parsed.document().isEmpty());
            assertTrue(parsed.diagnostics().stream().anyMatch(d -> d.ruleId().equals(expected.id())
                    && d.pointer().equals(pointer(position) + "/" + keyword)), parsed.diagnostics()::toString);
        }
    }

    /** Ignored siblings stay in source, but never introduce schema IDs or external reference edges. */
    @ParameterizedTest @MethodSource("positions")
    void referenceSiblingsProduceOneWarningAndAreNotInterpretedAsSchemas(BindingSchemaPositions.Position position) {
        ObjectNode reference = ref("#/components/schemas/Value");
        reference.put("$id", "urn:ignored");
        reference.set("default", ref("not-a-reference.json"));
        var parsed = parse(fixture(position, reference));
        assertTrue(parsed.successful(), parsed.diagnostics()::toString);
        assertEquals(1, parsed.diagnostics().size());
        var warning = parsed.diagnostics().get(0);
        assertEquals(BuiltinRuleId.REFERENCE_SIBLINGS.id(), warning.ruleId());
        assertEquals(io.github.sjacek.asyncwright.api.Severity.WARNING, warning.severity());
        assertEquals(pointer(position), warning.pointer());
        var document = parsed.document().orElseThrow();
        assertEquals(reference, document.source().at(pointer(position)));
        var mapped = MAPPER.valueToTree(document.model()).at(pointer(position));
        assertTrue(mapped.path("$id").isNull());
        assertTrue(mapped.path("default").isNull());
        assertEquals("#/components/schemas/Value", mapped.path("$ref").textValue());
    }

    /** A false target is a concrete schema, not a missing node or an unresolvable alias. */
    @ParameterizedTest @MethodSource("positions")
    void bindingReferencesCanResolveBooleanSchemasWithoutChangingTheReferenceModel(
            BindingSchemaPositions.Position position) throws Exception {
        for (boolean value : List.of(true, false)) {
            ObjectNode root = fixture(position, ref("#/components/schemas/Alias"));
            ((ObjectNode) root.at("/components/schemas")).put("Value", value);
            var parsed = parse(root);
            assertTrue(parsed.successful(), parsed.diagnostics()::toString);
            var document = parsed.document().orElseThrow();
            var target = document.references().resolve(pointer(position)).orElseThrow();
            assertTrue(target.node().isBoolean());
            assertEquals(value, target.node().booleanValue());
            var typed = document.references().resolve(pointer(position), AsyncAPISchema.class).orElseThrow();
            assertEquals(target.node(), MAPPER.valueToTree(typed));
            assertEquals("#/components/schemas/Alias",
                    MAPPER.valueToTree(document.model()).at(pointer(position) + "/$ref").textValue());
        }
    }

    @Test
    void registryExactlyCoversSchemaPositionsInEveryPinnedBindingVersion() throws Exception {
        Set<BindingSchemaPositions.Position> expected = new HashSet<>();
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream(AsyncApi30.SCHEMA_RESOURCE))) {
            for (var definition : MAPPER.readTree(input).path("definitions").properties()) {
                String id = definition.getKey();
                if (!id.startsWith("http://asyncapi.com/bindings/")) continue;
                String[] parts = id.substring("http://asyncapi.com/bindings/".length()).split("/");
                String protocol = parts[0].equals("websockets") ? "ws" : parts[0];
                var owner = switch (parts[2]) {
                    case "server.json" -> DocumentIndex.Kind.SERVER_BINDINGS;
                    case "channel.json" -> DocumentIndex.Kind.CHANNEL_BINDINGS;
                    case "operation.json" -> DocumentIndex.Kind.OPERATION_BINDINGS;
                    case "message.json" -> DocumentIndex.Kind.MESSAGE_BINDINGS;
                    default -> throw new AssertionError(id);
                };
                for (var field : definition.getValue().path("properties").properties())
                    if (field.getValue().findValuesAsText("$ref").contains(SCHEMA_REF))
                        expected.add(new BindingSchemaPositions.Position(protocol, owner, field.getKey()));
            }
        }
        assertEquals(expected, Set.copyOf(BindingSchemaPositions.ALL));
        assertEquals(expected.size(), BindingSchemaPositions.ALL.size(), "No duplicate declarations");
    }

    @Test
    void arbitraryBindingDataAndMqttScalarAlternativesDoNotBecomeSchemas() throws Exception {
        String input = """
                asyncapi: 3.0.0
                info: {title: Opaque data, version: 1.0.0}
                components:
                  channelBindings:
                    Shared:
                      kafka:
                        topicConfiguration:
                          nested: {key: {$ref: remote.json}, headers: {$ref: other.json}}
                  serverBindings:
                    Shared:
                      mqtt: {sessionExpiryInterval: 12, maximumPacketSize: 1024}
                """;
        var index = new DocumentIndex(SourceDocument.read("data.yaml", input));
        assertTrue(index.references.isEmpty());
        assertTrue(index.diagnostics.isEmpty());
        assertTrue(index.kinds.values().stream().noneMatch(kind -> kind == DocumentIndex.Kind.SCHEMA));
    }
}
