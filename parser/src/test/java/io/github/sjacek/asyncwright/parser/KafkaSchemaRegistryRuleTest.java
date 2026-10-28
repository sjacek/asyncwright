package io.github.sjacek.asyncwright.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sjacek.asyncwright.api.BuiltinRuleId;
import io.github.sjacek.asyncwright.api.Diagnostic;
import io.github.sjacek.asyncwright.api.Severity;
import io.github.sjacek.asyncwright.api.SourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Usage-sensitive registry checks must not confuse reusable definitions with operation contexts. */
class KafkaSchemaRegistryRuleTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AsyncApiParser parser = new AsyncApiParser();

    /** JSON fixtures are edited as trees so each test changes just its semantic boundary. */
    private static ObjectNode document() throws Exception {
        return (ObjectNode) MAPPER.readTree("""
                {"asyncapi":"3.0.0","info":{"title":"Registry","version":"1.0.0"},
                 "servers":{"broker":{"host":"localhost:9092","protocol":"kafka"}},
                 "channels":{"events":{"address":"events","messages":{
                   "registered":{"bindings":{"kafka":{"schemaIdLocation":"header","bindingVersion":"0.5.0"}},
                     "payload":{"type":"string"}},
                   "plain":{"payload":{"type":"string"}}}}},
                 "operations":{"send":{"action":"send","channel":{"$ref":"#/channels/events"}}}}
                """);
    }

    private ParseResult parse(ObjectNode document) throws Exception {
        return parser.parse("contracts/events.json", MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(document));
    }

    private static ObjectNode object(ObjectNode document, String pointer) {
        return assertInstanceOf(ObjectNode.class, document.at(pointer), pointer);
    }

    private static ObjectNode ref(String pointer) { return MAPPER.createObjectNode().put("$ref", "#" + pointer); }

    private static List<Diagnostic> registry(ParseResult result) {
        return result.diagnostics().stream().filter(d -> d.ruleId().equals(BuiltinRuleId.KAFKA_SCHEMA_REGISTRY.id())).toList();
    }

    private static Diagnostic onlyFinding(ParseResult result, Severity severity, String pointer) {
        assertEquals(severity == Severity.WARNING, result.successful(), result.diagnostics()::toString);
        assertEquals(severity == Severity.WARNING, result.document().isPresent());
        assertEquals(1, result.diagnostics().size(), result.diagnostics()::toString);
        assertEquals(1, registry(result).size(), result.diagnostics()::toString);
        Diagnostic diagnostic = registry(result).get(0);
        assertEquals(severity, diagnostic.severity());
        assertEquals(pointer, diagnostic.pointer());
        assertEquals("contracts/events.json", diagnostic.file());
        assertTrue(diagnostic.line() > 1);
        assertTrue(diagnostic.column() > 0);
        return diagnostic;
    }

    private static List<String> related(Diagnostic diagnostic) {
        return diagnostic.related().stream().map(SourceLocation::pointer).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"schemaIdLocation", "schemaIdPayloadEncoding", "schemaLookupStrategy"})
    void eachMetadataFieldRequiresRegistryOnTheActualServer(String field) throws Exception {
        var document = document();
        ObjectNode binding = object(document, "/channels/events/messages/registered/bindings/kafka");
        binding.remove("schemaIdLocation");
        binding.put(field, field.equals("schemaIdLocation") ? "header"
                : field.equals("schemaIdPayloadEncoding") ? "confluent" : "TopicNameStrategy");
        var diagnostic = onlyFinding(parse(document), Severity.ERROR,
                "/channels/events/messages/registered/bindings/kafka/" + field);
        assertEquals(List.of("/operations/send", "/servers/broker"), related(diagnostic));
    }

    @Test
    void vendorIsCheckedLocallyEvenOnAnUnusedComponentServer() throws Exception {
        var document = document();
        document.remove("operations");
        object(document, "/servers/broker").putObject("bindings").putObject("kafka")
                .put("schemaRegistryUrl", "https://registry.invalid");
        document.putObject("components").putObject("servers").putObject("unused")
                .put("host", "localhost").put("protocol", "kafka").putObject("bindings").putObject("kafka")
                .put("schemaRegistryVendor", "confluent").put("bindingVersion", "0.5.0");
        onlyFinding(parse(document), Severity.ERROR, "/components/servers/unused/bindings/kafka/schemaRegistryVendor");
        object(document, "/components/servers/unused/bindings/kafka").put("schemaRegistryUrl", "https://registry.invalid");
        assertTrue(parse(document).diagnostics().isEmpty());
    }

    @Test
    void acceptsCompleteRegistryAndDoesNotFetchItsUrl() throws Exception {
        var document = document();
        // The reserved .invalid URL is metadata. Successful parsing must not depend on DNS or HTTP.
        object(document, "/servers/broker").putObject("bindings").putObject("kafka")
                .put("schemaRegistryUrl", "https://registry.invalid/unreachable")
                .put("schemaRegistryVendor", "confluent").put("bindingVersion", "0.5.0");
        var result = parse(document);
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void unusedServerBindingDefinitionsStillNeedTheirLocalVendorDependency() throws Exception {
        var document = document();
        document.remove("operations");
        document.putObject("components").putObject("serverBindings").putObject("Unused")
                .putObject("kafka").put("schemaRegistryVendor", "confluent").put("bindingVersion", "0.5.0");
        onlyFinding(parse(document), Severity.ERROR, "/components/serverBindings/Unused/kafka/schemaRegistryVendor");
    }

    @Test
    void plainMessagesAndInstanceDataDoNotRequireRegistry() throws Exception {
        var document = document();
        object(document, "/channels/events/messages/registered").remove("bindings");
        object(document, "/channels/events/messages/registered/payload").putObject("default")
                .putObject("bindings").putObject("kafka").put("schemaIdLocation", "header");
        document.putObject("x-data").put("schemaRegistryVendor", "confluent");
        assertTrue(parse(document).diagnostics().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentOrEmptyServerSelectionChecksAllRootKafkaServers(boolean emptyList) throws Exception {
        var document = document();
        if (emptyList) object(document, "/channels/events").putArray("servers");
        object(document, "/servers/broker").putObject("bindings").putObject("kafka")
                .put("schemaRegistryUrl", "https://registry.invalid");
        object(document, "/servers").putObject("secure").put("host", "localhost").put("protocol", "kafka-secure");
        object(document, "/servers").putObject("other").put("host", "localhost").put("protocol", "amqp");
        var diagnostic = onlyFinding(parse(document), Severity.ERROR,
                "/channels/events/messages/registered/bindings/kafka/schemaIdLocation");
        assertEquals(List.of("/operations/send", "/servers/secure"), related(diagnostic));
    }

    @Test
    void explicitServerSelectionDoesNotBorrowUrlFromAnotherServer() throws Exception {
        var document = document();
        object(document, "/servers").putObject("unused").put("host", "localhost").put("protocol", "kafka")
                .putObject("bindings").putObject("kafka").put("schemaRegistryUrl", "https://registry.invalid");
        object(document, "/channels/events").putArray("servers").add(ref("/servers/broker"));
        onlyFinding(parse(document), Severity.ERROR, "/channels/events/messages/registered/bindings/kafka/schemaIdLocation");
        object(document, "/channels/events").putArray("servers").add(ref("/servers/unused"));
        assertTrue(parse(document).diagnostics().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentOrEmptyMessageSelectionChecksAllChannelMessages(boolean emptyList) throws Exception {
        var document = document();
        if (emptyList) object(document, "/operations/send").putArray("messages");
        onlyFinding(parse(document), Severity.ERROR, "/channels/events/messages/registered/bindings/kafka/schemaIdLocation");
    }

    @Test
    void explicitMessagesExcludeUnselectedChannelMessagesAndUnusedComponents() throws Exception {
        var document = document();
        object(document, "/operations/send").putArray("messages").add(ref("/channels/events/messages/plain"));
        document.putObject("components").putObject("messages").set("unused",
                document.at("/channels/events/messages/registered").deepCopy());
        assertTrue(parse(document).diagnostics().isEmpty());
        document.remove("operations");
        assertTrue(parse(document).diagnostics().isEmpty(), "Channel definitions alone are not usages");
    }

    @Test
    void unusedComponentOperationsDoNotCreateContexts() throws Exception {
        var document = document();
        document.putObject("components").set("operations", document.remove("operations"));
        assertTrue(parse(document).diagnostics().isEmpty());
    }

    @Test
    void noKafkaServerWarnsRatherThanSilentlyAcceptingTheMetadata() throws Exception {
        var document = document();
        object(document, "/servers/broker").put("protocol", "amqp");
        var warning = onlyFinding(parse(document), Severity.WARNING,
                "/channels/events/messages/registered/bindings/kafka/schemaIdLocation");
        assertEquals(List.of("/channels/events", "/operations/send"), related(warning));
        document.remove("servers");
        onlyFinding(parse(document), Severity.WARNING, warning.pointer());
    }

    @Test
    void sharedBindingsAggregateOperationsAndFieldsButKeepDistinctServers() throws Exception {
        var document = document();
        var binding = object(document, "/channels/events/messages/registered/bindings/kafka");
        binding.put("schemaIdPayloadEncoding", "confluent").put("schemaLookupStrategy", "TopicNameStrategy");
        object(document, "/operations").set("receive", object(document, "/operations/send").deepCopy().put("action", "receive"));
        object(document, "/servers").putObject("secure").put("host", "localhost").put("protocol", "kafka-secure");
        var result = parse(document);
        assertFalse(result.successful());
        assertEquals(2, result.diagnostics().size(), result.diagnostics()::toString);
        assertEquals(Set.of(List.of("/operations/receive", "/operations/send", "/servers/broker"),
                        List.of("/operations/receive", "/operations/send", "/servers/secure")),
                Set.copyOf(registry(result).stream().map(KafkaSchemaRegistryRuleTest::related).toList()));
        assertEquals(result.diagnosticsJson(), parse(document).diagnosticsJson());
    }

    @Test
    void resolvesAllAliasKindsAndRetainsSourceReferences() throws Exception {
        var document = document();
        var components = document.putObject("components");
        components.set("channels", document.remove("channels"));
        document.putObject("channels").set("events", ref("/components/channels/events"));
        components.set("servers", document.remove("servers"));
        document.putObject("servers").set("broker", ref("/components/servers/broker"));
        components.set("operations", document.remove("operations"));
        document.putObject("operations").set("send", ref("/components/operations/send"));
        var message = object(document, "/components/channels/events/messages/registered");
        components.putObject("messageBindings").set("shared", message.remove("bindings"));
        object(document, "/components/messageBindings").set("alias", ref("/components/messageBindings/shared"));
        message.set("bindings", ref("/components/messageBindings/alias"));
        components.putObject("messages").set("shared", message.deepCopy());
        object(document, "/components/channels/events/messages").set("registered", ref("/components/messages/shared"));
        var bad = onlyFinding(parse(document), Severity.ERROR, "/components/messageBindings/shared/kafka/schemaIdLocation");
        assertEquals(List.of("/components/servers/broker", "/operations/send"), related(bad));
        components.putObject("serverBindings").putObject("shared").putObject("kafka")
                .put("schemaRegistryUrl", "https://registry.invalid").put("bindingVersion", "0.5.0");
        object(document, "/components/servers/broker").set("bindings", ref("/components/serverBindings/shared"));
        var result = parse(document);
        assertTrue(result.successful(), result.diagnostics()::toString);
        var parsed = result.document().orElseThrow();
        assertEquals("#/components/operations/send", parsed.source().at("/operations/send/$ref").asText());
        assertEquals("#/components/messageBindings/alias", parsed.source().at("/components/messages/shared/bindings/$ref").asText());
        assertEquals("/components/messageBindings/shared", parsed.references().resolve("/components/messages/shared/bindings").orElseThrow().pointer());
    }

    @Test
    void replyUsesItsOwnChannelServersAndSelectedMessages() throws Exception {
        var document = document();
        object(document, "/servers").putObject("replyBroker").put("host", "localhost").put("protocol", "kafka");
        object(document, "/servers/broker").putObject("bindings").putObject("kafka")
                .put("schemaRegistryUrl", "https://registry.invalid");
        object(document, "/channels/events").putArray("servers").add(ref("/servers/broker"));
        object(document, "/channels").set("replies", object(document, "/channels/events").deepCopy());
        object(document, "/channels/replies").putArray("servers").add(ref("/servers/replyBroker"));
        var reply = object(document, "/operations/send").putObject("reply");
        reply.set("channel", ref("/channels/replies"));
        reply.putArray("messages").add(ref("/channels/replies/messages/registered"));
        var diagnostic = onlyFinding(parse(document), Severity.ERROR,
                "/channels/replies/messages/registered/bindings/kafka/schemaIdLocation");
        assertEquals(List.of("/operations/send", "/servers/replyBroker"), related(diagnostic));
        reply.putArray("messages").add(ref("/channels/replies/messages/plain"));
        assertTrue(parse(document).diagnostics().isEmpty());
        reply.putArray("messages");
        onlyFinding(parse(document), Severity.ERROR, diagnostic.pointer());
    }

    @Test
    void dynamicReplyFallsBackToRequestChannelServersAndResolvesReplyAliases() throws Exception {
        var document = document();
        object(document, "/operations/send").putArray("messages").add(ref("/channels/events/messages/plain"));
        var reply = document.putObject("components").putObject("replies").putObject("dynamic");
        reply.putObject("address").put("location", "$message.header#/replyTo");
        reply.putArray("messages").add(ref("/channels/events/messages/registered"));
        object(document, "/operations/send").set("reply", ref("/components/replies/dynamic"));
        onlyFinding(parse(document), Severity.ERROR, "/channels/events/messages/registered/bindings/kafka/schemaIdLocation");
    }

    @ParameterizedTest
    @ValueSource(strings = {"server", "channel", "message", "bindings", "reply-channel"})
    void invalidDependenciesKeepReferenceErrorsWithoutSecondaryRegistryFindings(String dependency) throws Exception {
        var document = document();
        switch (dependency) {
            case "server" -> object(document, "/channels/events").putArray("servers").add(ref("/servers/missing"));
            case "channel" -> object(document, "/operations/send").set("channel", ref("/channels/missing"));
            case "message" -> object(document, "/operations/send").putArray("messages").add(ref("/components/messages/missing"));
            case "bindings" -> object(document, "/servers/broker").set("bindings", ref("/components/serverBindings/missing"));
            case "reply-channel" -> {
                object(document, "/operations/send").putArray("messages").add(ref("/channels/events/messages/plain"));
                object(document, "/operations/send").putObject("reply").set("channel", ref("/channels/missing"));
            }
            default -> fail(dependency);
        }
        var result = parse(document);
        assertFalse(result.successful());
        assertTrue(registry(result).isEmpty(), result.diagnostics()::toString);
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.ruleId().equals(BuiltinRuleId.REFERENCE_MISSING.id())),
                result.diagnostics()::toString);
    }

    @Test
    void yamlReportsDefinitionCoordinatesAndAggregatesVendorMapAliases() {
        var result = parser.parse("contracts/events.json", """
                asyncapi: 3.0.0
                info: {title: Registry, version: 1.0.0}
                servers:
                  first:
                    host: localhost
                    protocol: kafka
                    bindings: {$ref: '#/components/serverBindings/Shared'}
                  second:
                    host: localhost
                    protocol: kafka-secure
                    bindings: {$ref: '#/components/serverBindings/Shared'}
                components:
                  serverBindings:
                    Shared:
                      kafka:
                        schemaRegistryVendor: confluent
                        bindingVersion: 0.5.0
                """);
        var diagnostic = onlyFinding(result, Severity.ERROR, "/components/serverBindings/Shared/kafka/schemaRegistryVendor");
        assertEquals(16, diagnostic.line());
        assertEquals(9, diagnostic.column());
        assertEquals(List.of("/servers/first", "/servers/first/bindings/$ref", "/servers/second", "/servers/second/bindings/$ref"), related(diagnostic));
    }

    @Test
    void invalidRequestChannelDoesNotSuppressAnIndependentExplicitReplyContext() throws Exception {
        var document = document();
        var operation = object(document, "/operations/send");
        operation.set("channel", ref("/channels/missing"));
        operation.putObject("reply").set("channel", ref("/channels/events"));
        var result = parse(document);
        assertFalse(result.successful());
        assertTrue(result.document().isEmpty());
        assertEquals(2, result.diagnostics().size(), result.diagnostics()::toString);
        assertEquals(1, registry(result).size());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.ruleId().equals(BuiltinRuleId.REFERENCE_MISSING.id())));
        assertEquals(List.of("/operations/send", "/servers/broker"), related(registry(result).get(0)));
    }

    @Test
    void parserReuseDoesNotCarryRegistryFindingsToAnotherDocument() throws Exception {
        assertFalse(parse(document()).successful());
        var clean = parser.parse("other.json", "{\"asyncapi\":\"3.0.0\",\"info\":{\"title\":\"Other\",\"version\":\"1\"}}");
        assertTrue(clean.successful());
        assertTrue(clean.diagnostics().isEmpty());
    }

    /** Distinct public server aliases must not multiply one concrete server dependency. */
    @Test
    void rootServerAliasesAreDeduplicatedByConcreteDefinition() throws Exception {
        var document = document();
        var server = object(document, "/servers/broker").deepCopy();
        document.putObject("components").putObject("servers").set("Shared", server);
        object(document, "/servers").set("broker", ref("/components/servers/Shared"));
        object(document, "/servers").set("alias", ref("/components/servers/Shared"));
        object(document, "/channels/events").putArray("servers")
                .add(ref("/servers/broker")).add(ref("/servers/alias"));
        var diagnostic = onlyFinding(parse(document), Severity.ERROR,
                "/channels/events/messages/registered/bindings/kafka/schemaIdLocation");
        assertEquals(List.of("/components/servers/Shared", "/operations/send"), related(diagnostic));
    }

    /** One shared definition can warn in AMQP usages and fail in a Kafka usage at the same time. */
    @Test
    void sharedBindingKeepsWarningContextsSeparateFromTheMissingUrlError() throws Exception {
        var document = document();
        var components = document.putObject("components");
        var message = object(document, "/channels/events/messages/registered");
        components.putObject("messageBindings").set("Shared", message.remove("bindings"));
        message.set("bindings", ref("/components/messageBindings/Shared"));
        object(document, "/servers").putObject("amqp").put("host", "localhost").put("protocol", "amqp");
        object(document, "/channels/events").putArray("servers").add(ref("/servers/broker"));
        for (String name : List.of("other", "third")) {
            object(document, "/channels").set(name, object(document, "/channels/events").deepCopy());
            object(document, "/channels/" + name).putArray("servers").add(ref("/servers/amqp"));
            object(document, "/operations").putObject(name).put("action", "receive")
                    .set("channel", ref("/channels/" + name));
        }
        var result = parse(document);
        assertFalse(result.successful());
        assertTrue(result.document().isEmpty());
        assertEquals(2, result.diagnostics().size(), result.diagnostics()::toString);
        var error = registry(result).stream().filter(d -> d.severity() == Severity.ERROR).findFirst().orElseThrow();
        var warning = registry(result).stream().filter(d -> d.severity() == Severity.WARNING).findFirst().orElseThrow();
        assertEquals("/components/messageBindings/Shared/kafka/schemaIdLocation", error.pointer());
        assertEquals(error.pointer(), warning.pointer());
        assertEquals(List.of("/operations/send", "/servers/broker"), related(error));
        assertEquals(List.of("/channels/other", "/channels/third", "/operations/other", "/operations/third"), related(warning));
        assertEquals(result.diagnosticsJson(), parse(document).diagnosticsJson());
    }

    /** Dynamic replies fall back to the channel's message set, not the request's selected subset. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dynamicReplyWithAbsentOrEmptyMessagesUsesAllRequestChannelMessages(boolean emptyList) throws Exception {
        var document = document();
        var operation = object(document, "/operations/send");
        operation.putArray("messages").add(ref("/channels/events/messages/plain"));
        var reply = operation.putObject("reply");
        reply.putObject("address").put("location", "$message.header#/replyTo");
        if (emptyList) reply.putArray("messages");
        onlyFinding(parse(document), Severity.ERROR,
                "/channels/events/messages/registered/bindings/kafka/schemaIdLocation");
    }

    /** Siblings cannot rescue a referenced server or add metadata to a referenced plain message. */
    @ParameterizedTest
    @ValueSource(strings = {"server", "message"})
    void ignoredReferenceSiblingsCannotCreateOrSatisfyRegistryDependencies(String owner) throws Exception {
        var document = document();
        var components = document.putObject("components");
        if (owner.equals("server")) {
            components.putObject("servers").set("Shared", object(document, "/servers/broker").deepCopy());
            var alias = ref("/components/servers/Shared");
            alias.putObject("bindings").putObject("kafka").put("schemaRegistryUrl", "https://registry.invalid");
            object(document, "/servers").set("broker", alias);
        } else {
            components.putObject("messages").set("Plain", object(document, "/channels/events/messages/plain").deepCopy());
            var alias = ref("/components/messages/Plain");
            alias.set("bindings", object(document, "/channels/events/messages/registered/bindings").deepCopy());
            object(document, "/channels/events/messages").set("registered", alias);
        }
        var result = parse(document);
        assertEquals(owner.equals("message"), result.successful(), result.diagnostics()::toString);
        assertEquals(owner.equals("server") ? 1 : 0, registry(result).size(), result.diagnostics()::toString);
        assertEquals(1, result.diagnostics().stream().filter(d -> d.ruleId().equals(BuiltinRuleId.REFERENCE_SIBLINGS.id())).count());
    }

    /** Unknown selected servers suppress only the no-server warning, not known bad Kafka servers. */
    @Test
    void unresolvedAndKnownServersRetainIndependentRegistryErrors() throws Exception {
        var document = document();
        object(document, "/channels/events").putArray("servers")
                .add(ref("/servers/missing")).add(ref("/servers/broker"));
        var result = parse(document);
        assertFalse(result.successful());
        assertEquals(2, result.diagnostics().size(), result.diagnostics()::toString);
        assertEquals(1, registry(result).size());
        assertEquals(Severity.ERROR, registry(result).get(0).severity());
        assertEquals(List.of("/operations/send", "/servers/broker"), related(registry(result).get(0)));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.ruleId().equals(BuiltinRuleId.REFERENCE_MISSING.id())));
    }

    /** Wrong-kind and cyclic aliases must remain reference failures, never missing-URL findings. */
    @ParameterizedTest
    @ValueSource(strings = {"server-bindings-kind", "message-bindings-cycle", "reply-cycle"})
    void invalidAliasGraphsDoNotProduceSecondaryRegistryFindings(String dependency) throws Exception {
        var document = document();
        var components = document.putObject("components");
        BuiltinRuleId expected = switch (dependency) {
            case "server-bindings-kind" -> {
                components.putObject("messageBindings").putObject("Other").putObject("kafka").put("bindingVersion", "0.5.0");
                object(document, "/servers/broker").set("bindings", ref("/components/messageBindings/Other"));
                yield BuiltinRuleId.REFERENCE_TYPE;
            }
            case "message-bindings-cycle" -> {
                var maps = components.putObject("messageBindings");
                maps.set("A", ref("/components/messageBindings/B"));
                maps.set("B", ref("/components/messageBindings/A"));
                object(document, "/channels/events/messages/registered").set("bindings", ref("/components/messageBindings/A"));
                yield BuiltinRuleId.REFERENCE_CYCLE;
            }
            case "reply-cycle" -> {
                var replies = components.putObject("replies");
                replies.set("A", ref("/components/replies/B"));
                replies.set("B", ref("/components/replies/A"));
                object(document, "/operations/send").putArray("messages").add(ref("/channels/events/messages/plain"));
                object(document, "/operations/send").set("reply", ref("/components/replies/A"));
                yield BuiltinRuleId.REFERENCE_CYCLE;
            }
            default -> throw new AssertionError(dependency);
        };
        var result = parse(document);
        assertFalse(result.successful());
        assertTrue(result.document().isEmpty());
        assertTrue(registry(result).isEmpty(), result.diagnostics()::toString);
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.ruleId().equals(expected.id())), result.diagnostics()::toString);
    }

    @Test
    void structuralErrorsStopRegistryValidationBeforeLocalAndUsageChecks() throws Exception {
        var document = document();
        object(document, "/channels/events/messages/registered/bindings/kafka").put("schemaIdLocation", "invalid");
        object(document, "/servers/broker").putObject("bindings").putObject("kafka")
                .put("schemaRegistryVendor", "confluent").put("bindingVersion", "0.5.0");
        var result = parse(document);
        assertFalse(result.successful());
        assertTrue(result.document().isEmpty());
        assertFalse(result.diagnostics().isEmpty());
        assertTrue(result.diagnostics().stream().allMatch(d -> d.ruleId().startsWith("schema.")), result.diagnostics()::toString);
    }

    /** The primary field is a documented precedence, independent of JSON property insertion order. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void metadataPrecedenceIsStableWhenSourcePropertiesAppearInReverseOrder(boolean locationPresent) throws Exception {
        var document = document();
        var binding = object(document, "/channels/events/messages/registered/bindings/kafka");
        binding.removeAll();
        binding.put("schemaLookupStrategy", "TopicNameStrategy").put("schemaIdPayloadEncoding", "confluent");
        if (locationPresent) binding.put("schemaIdLocation", "header");
        binding.put("bindingVersion", "0.5.0");
        onlyFinding(parse(document), Severity.ERROR,
                "/channels/events/messages/registered/bindings/kafka/"
                        + (locationPresent ? "schemaIdLocation" : "schemaIdPayloadEncoding"));
    }

    /** One shared parser must not mix finding severity or source/related file names between calls. */
    @Test
    void parallelParserCallsKeepRegistryStateAndLocationsPerDocument() throws Exception {
        var invalid = document();
        var complete = invalid.deepCopy();
        object(complete, "/servers/broker").putObject("bindings").putObject("kafka")
                .put("schemaRegistryUrl", "https://registry.invalid");
        var warning = invalid.deepCopy();
        warning.remove("servers");
        List<String> inputs = List.of(MAPPER.writeValueAsString(invalid), MAPPER.writeValueAsString(complete), MAPPER.writeValueAsString(warning));
        IntStream.range(0, 12).parallel().forEach(number -> {
            int kind = number % inputs.size();
            String file = "parallel/events-" + number + ".json";
            var result = parser.parse(file, inputs.get(kind));
            assertEquals(kind != 0, result.successful(), result.diagnostics()::toString);
            assertEquals(kind == 1 ? 0 : 1, result.diagnostics().size(), result.diagnostics()::toString);
            for (Diagnostic diagnostic : result.diagnostics()) {
                assertEquals(file, diagnostic.file());
                assertEquals(kind == 0 ? Severity.ERROR : Severity.WARNING, diagnostic.severity());
                assertTrue(diagnostic.related().stream().allMatch(location -> location.file().equals(file)));
            }
        });
    }
}
