package io.github.sjacek.asyncwright.parser;

import com.asyncapi.bindings.amqp.AMQPChannelBinding;
import com.asyncapi.v3._0_0.model.channel.Channel;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sjacek.asyncwright.api.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AsyncApiParserTest {
    private final AsyncApiParser parser = new AsyncApiParser();

    private ParseResult parse(String body) {
        return parser.parse("events.yaml", "{\"asyncapi\":\"3.0.0\",\"info\":{\"title\":\"Events\",\"version\":\"1.0.0\"}," + body + "}");
    }

    private static void error(ParseResult result, String rule) {
        assertFalse(result.successful(), result.diagnostics()::toString);
        assertTrue(result.document().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.ruleId().equals(rule)), result.diagnostics()::toString);
    }

    @Test
    void retainsTypedModelReferencesAndSourcePositions() throws Exception {
        var result = parser.parse("specs/events.yaml", """
                asyncapi: 3.0.0
                info:
                  title: Events
                  version: 1.0.0
                channels:
                  events:
                    address: events
                    bindings:
                      amqp:
                        is: routingKey
                        exchange:
                          name: events
                          type: topic
                        bindingVersion: 0.3.0
                operations:
                  send:
                    action: send
                    channel:
                      $ref: '#/channels/events'
                """);
        assertTrue(result.successful(), result.diagnostics()::toString);
        var document = result.document().orElseThrow();
        var operations = document.model().getOperations();
        assertNotNull(operations, "Expected operations in the typed model");
        var operation = assertInstanceOf(com.asyncapi.v3._0_0.model.operation.Operation.class,
                operations.get("send"));
        assertEquals("#/channels/events", operation.getChannel().getRef());
        Channel channel = document.references().resolve("/operations/send/channel", Channel.class).orElseThrow();
        assertEquals("events", channel.getAddress());
        var bindings = channel.getBindings();
        assertNotNull(bindings, "Expected channel bindings in the typed model");
        assertInstanceOf(AMQPChannelBinding.class, bindings.get("amqp"));
        assertEquals(19, document.location("/operations/send/channel/$ref").line());
        assertEquals("specs/events.yaml", document.location("/channels/events").file());
        assertTrue(document.source().at("/operations/send/channel").has("$ref"));
    }

    @Test
    void schemaErrorsStopReferenceAndSemanticValidation() {
        var result = parser.parse("events.yaml", """
                asyncapi: 3.0.0
                info:
                  title: 123
                  version: 1.0.0
                operations:
                  send:
                    action: send
                    channel: { $ref: '#/channels/missing' }
                """);
        error(result, "schema.type");
        var diagnostic = result.diagnostics().stream().filter(d -> d.pointer().equals("/info/title")).findFirst().orElseThrow();
        assertEquals(3, diagnostic.line());
        assertTrue(result.diagnostics().stream().noneMatch(d -> d.ruleId().startsWith("reference.")));
    }

    @Test
    void missingTargetPointsToTheUse() {
        var result = parse("\"operations\":{\"send\":{\"action\":\"send\",\"channel\":{\"$ref\":\"#/channels/missing\"}}}");
        error(result, "reference.missing");
        assertEquals("/operations/send/channel/$ref", result.diagnostics().get(0).pointer());
    }

    @Test
    void rejectsWrongTargetKind() {
        var result = parse("""
                "components":{"messages":{"m":{"payload":{"type":"string"}}}},
                "operations":{"send":{"action":"send","channel":{"$ref":"#/components/messages/m"}}}
                """);
        error(result, "reference.type");
    }

    @Test
    void allowsRecursiveSchemasAndKeepsSourceSeparateFromResolvedCopies() {
        var result = parse("""
                "components":{"schemas":{"Node":{"type":"object","properties":{
                  "child":{"$ref":"#/components/schemas/Node"}}}}}
                """);
        assertTrue(result.successful(), result.diagnostics()::toString);
        var document = result.document().orElseThrow();
        String use = "/components/schemas/Node/properties/child";
        var first = document.references().resolve(use).orElseThrow();
        assertEquals("/components/schemas/Node", first.pointer());
        ((ObjectNode)first.node()).removeAll();
        assertTrue(document.references().resolve(use).orElseThrow().node().has("properties"));
        assertEquals("#/components/schemas/Node", document.source().at(use + "/$ref").asText());
    }

    @Test
    void rejectsPureReferenceCyclesInsideAndOutsideSchemas() {
        error(parse("\"components\":{\"schemas\":{\"A\":{\"$ref\":\"#/components/schemas/B\"},\"B\":{\"$ref\":\"#/components/schemas/A\"}}}"), "reference.cycle");
        error(parse("\"channels\":{\"a\":{\"$ref\":\"#/channels/b\"},\"b\":{\"$ref\":\"#/channels/a\"}}"), "reference.cycle");
    }

    @Test
    void resolvesEscapedAndPercentEncodedTokensWithoutChangingPlusSigns() {
        var result = parse("""
                "components":{"schemas":{
                  "Root":{"type":"object","properties":{"a/b~ c+d":{"type":"string"}}},
                  "Alias":{"$ref":"#/components/schemas/Root/properties/a~1b~0%20c+d"}}}
                """);
        assertTrue(result.successful(), result.diagnostics()::toString);
        var target = result.document().orElseThrow().references().resolve("/components/schemas/Alias").orElseThrow();
        assertEquals("string", target.node().path("type").asText());
    }

    @Test
    void rejectsMalformedPointerEscapesAndExternalReferences() {
        error(parse("\"components\":{\"schemas\":{\"A\":{\"$ref\":\"#/components/schemas/B~2\"}}}"), "reference.pointer");
        error(parse("\"components\":{\"schemas\":{\"A\":{\"$ref\":\"https://unregistered.invalid/schema.json\"}}}"), "unsupported.reference");
    }

    @Test
    void ignoresRefLikeDataInsideExamplesAndExtensions() {
        var result = parse("""
                "x-data":{"$ref":"https://unregistered.invalid/data"},
                "components":{"schemas":{"Value":{"type":"object","examples":[{"$ref":"external.json"}],
                  "properties":{"examples":{"$ref":"#/components/schemas/Text"}}},"Text":{"type":"string"}}}
                """);
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertTrue(result.document().orElseThrow().references().resolve("/components/schemas/Value/properties/examples").isPresent());
    }

    @Test
    void warnsAboutIgnoredSiblingsWithoutChangingTheSource() {
        var result = parse("""
                "channels":{"events":{"address":"events"}},
                "operations":{"send":{"action":"send","channel":{"$ref":"#/channels/events","ignored":42}}}
                """);
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertEquals(Severity.WARNING, result.diagnostics().get(0).severity());
        assertTrue(result.document().orElseThrow().source().at("/operations/send/channel").has("ignored"));
    }

    @Test
    void validatesOperationMessageMembershipAndChannelParameters() {
        var result = parse("""
                "channels":{"events":{"address":"events","messages":{"first":{"payload":{"type":"string"}}}}},
                "components":{"messages":{"other":{"payload":{"type":"string"}}}},
                "operations":{"send":{"action":"send","channel":{"$ref":"#/channels/events"},
                  "messages":[{"$ref":"#/components/messages/other"}]}}
                """);
        error(result, "operation.message");
        error(parse("\"channels\":{\"events\":{\"address\":\"events/{tenant}\"}}"), "channel.parameter");
        assertTrue(parse("\"channels\":{\"events\":{\"address\":\"events/{tenant}\",\"parameters\":{\"tenant\":{}}}} ").successful());
    }

    @Test
    void rejectsTraitsAndNonDefaultSchemaFormats() {
        error(parse("\"components\":{\"messages\":{\"m\":{\"traits\":[{\"summary\":\"Trait\"}]}}}"), "unsupported.construct");
        error(parse("\"components\":{\"operationTraits\":{\"base\":{\"summary\":\"Trait\"}}}"), "unsupported.construct");
        error(parse("\"components\":{\"messages\":{\"m\":{\"payload\":{\"schemaFormat\":\"application/vnd.apache.avro+json;version=1.9.0\",\"schema\":{\"type\":\"string\"}}}}}"), "unsupported.construct");
    }

    @Test
    void acceptsTheExplicitDefaultSchemaFormat() {
        var result = parse("""
                "components":{"schemas":{"Text":{"type":"string"}},"messages":{"m":{"payload":{
                  "schemaFormat":"application/vnd.aai.asyncapi+json;version=3.0.0",
                  "schema":{"$ref":"#/components/schemas/Text"}}}}}
                """);
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertTrue(result.document().orElseThrow().references().resolve("/components/messages/m/payload/schema").isPresent());
    }

    @Test
    void syntaxErrorsDuplicateFieldsAndAliasesNeverProduceAModel() {
        error(parser.parse("events.yaml", "info: ["), "syntax");
        error(parser.parse("events.json", "{\"asyncapi\":\"3.0.0\",\"asyncapi\":\"3.0.0\"}"), "syntax");
        var alias = parser.parse("events.yaml", "asyncapi: 3.0.0\ninfo: {title: &title Events, version: *title}");
        error(alias, "unsupported.yaml-alias");
        assertEquals(2, alias.diagnostics().get(0).line());
    }

    @Test
    void diagnosticsAreRelativeAndStableAcrossProjectLocations(@TempDir Path directory) throws Exception {
        String input = "asyncapi: 3.0.0\ninfo: {title: 42, version: 1.0.0}";
        Path first = Files.createDirectories(directory.resolve("first/specs"));
        Path second = Files.createDirectories(directory.resolve("second/specs"));
        Files.writeString(first.resolve("events.yaml"), input);
        Files.writeString(second.resolve("events.yaml"), input);
        var a = parser.parse(first.getParent(), Path.of("specs/events.yaml"));
        var b = parser.parse(second.getParent(), Path.of("specs/events.yaml"));
        assertEquals(a.diagnosticsJson(), b.diagnosticsJson());
        assertTrue(a.diagnosticsJson().contains("\"severity\" : \"error\""));
        assertFalse(a.diagnosticsJson().contains(directory.toString()));
    }

    @Test
    void modelMappingFailuresPreserveTheSchemaPathAndRelatedUses() {
        var result = parse("""
                "components":{"schemas":{"Value":{"type":"string","nullable":true}},
                  "messages":{"one":{"payload":{"$ref":"#/components/schemas/Value"}},
                              "two":{"payload":{"$ref":"#/components/schemas/Value"}}}}
                """);
        error(result, "model.mapping");
        assertEquals("/components/schemas/Value/nullable", result.diagnostics().get(0).pointer());
        assertEquals(2, result.diagnostics().get(0).related().size());
    }

    @Test
    void unsupportedVersionsAreExplicit() {
        error(parser.parse("events.yaml", "asyncapi: 2.6.0\ninfo: {title: Events, version: 1.0.0}"), "unsupported.version");
        error(parser.parse("events.yaml", "asyncapi: 3.1.0\ninfo: {title: Events, version: 1.0.0}"), "unsupported.version");
    }

    @Test
    void definitionErrorsIncludeUsesThroughAliasesWithoutRevalidatingTheDefinition() {
        var result = parse("""
                "components":{"schemas":{"Value":{"type":17},"Alias":{"$ref":"#/components/schemas/Value"}},
                  "messages":{"one":{"payload":{"$ref":"#/components/schemas/Alias"}},
                              "two":{"payload":{"$ref":"#/components/schemas/Value"}}}}
                """);
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().allMatch(d -> d.pointer().startsWith("/components/schemas/Value")));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.related().size() == 3), result.diagnostics()::toString);
    }

    @Test
    void silentModelDataLossIsReportedInsteadOfReturningAnIncompleteModel() {
        var result = parse("\"channels\":{\"events\":{\"address\":\"events\",\"bindings\":{\"x-custom\":{\"option\":true}}}}");
        error(result, "model.preservation");
        assertEquals("/channels/events/bindings/x-custom", result.diagnostics().get(0).pointer());
    }
}
