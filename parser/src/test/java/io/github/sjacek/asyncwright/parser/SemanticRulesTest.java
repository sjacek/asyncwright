package io.github.sjacek.asyncwright.parser;

import com.asyncapi.v3._0_0.model.channel.Channel;
import com.asyncapi.v3._0_0.model.server.Server;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SemanticRulesTest {
    private final AsyncApiParser parser = new AsyncApiParser();

    private ParseResult parse(String body) {
        return parser.parse("events.json", "{\"asyncapi\":\"3.0.0\",\"info\":{\"title\":\"Events\",\"version\":\"1.0.0\"}," + body + "}");
    }

    private static void rejected(ParseResult result, String rule, String pointer) {
        assertFalse(result.successful(), result.diagnostics()::toString);
        assertTrue(result.document().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.ruleId().equals(rule) && d.pointer().equals(pointer)),
                result.diagnostics()::toString);
    }

    @Test
    void redundantParametersPointToTheirDefinitionInRootAndComponentChannels() {
        var root = parse("\"channels\":{\"events\":{\"address\":\"events/{tenant}\",\"parameters\":{\"tenant\":{},\"unused\":{}}}}");
        rejected(root, "channel.parameter", "/channels/events/parameters/unused");
        var component = parse("\"components\":{\"channels\":{\"events\":{\"address\":\"events\",\"parameters\":{\"unused\":{}}}}}");
        rejected(component, "channel.parameter", "/components/channels/events/parameters/unused");
    }

    @Test
    void parametersAreRedundantWhenTheAddressIsMissingOrNull() {
        rejected(parse("\"channels\":{\"events\":{\"parameters\":{\"unused\":{}}}}"),
                "channel.parameter", "/channels/events/parameters/unused");
        rejected(parse("\"channels\":{\"events\":{\"address\":null,\"parameters\":{\"unused\":{}}}}"),
                "channel.parameter", "/channels/events/parameters/unused");
    }

    @Test
    void aRepeatedAddressParameterNeedsOnlyOneDefinition() {
        var result = parse("\"channels\":{\"events\":{\"address\":\"events/{tenant}/{tenant}\",\"parameters\":{\"tenant\":{}}}}");
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void rootOperationCannotReferenceAComponentChannelEvenThroughAnAlias() {
        var result = parse("""
                "channels":{"events":{"address":"events"}},
                "components":{"channels":{"alias":{"$ref":"#/channels/events"}}},
                "operations":{"send":{"action":"send","channel":{"$ref":"#/components/channels/alias"}}}
                """);
        rejected(result, "operation.channel", "/operations/send/channel/$ref");
        var diagnostic = result.diagnostics().stream().filter(d -> d.ruleId().equals("operation.channel")).findFirst().orElseThrow();
        assertEquals("/components/channels/alias", diagnostic.related().get(0).pointer());
    }

    @Test
    void rootChannelChecksEveryServerAndCannotReferenceAComponentAlias() {
        var result = parse("""
                "servers":{"broker":{"host":"localhost","protocol":"kafka"}},
                "components":{"servers":{"alias":{"$ref":"#/servers/broker"}}},
                "channels":{"events":{"address":"events","servers":[
                  {"$ref":"#/servers/broker"},{"$ref":"#/components/servers/alias"}]}}
                """);
        rejected(result, "channel.server", "/channels/events/servers/1/$ref");
        var diagnostic = result.diagnostics().stream().filter(d -> d.ruleId().equals("channel.server")).findFirst().orElseThrow();
        assertEquals("/components/servers/alias", diagnostic.related().get(0).pointer());
    }

    @Test
    void componentDefinitionsMayReferToOtherComponents() {
        var result = parse("""
                "components":{
                  "servers":{"broker":{"host":"localhost","protocol":"kafka"}},
                  "channels":{"events":{"address":"events","servers":[{"$ref":"#/components/servers/broker"}]}},
                  "operations":{"send":{"action":"send","channel":{"$ref":"#/components/channels/events"}}}}
                """);
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void rootReferencesMayTargetRootAliasesWithoutExpandingTheModel() throws Exception {
        var result = parse("""
                "servers":{"broker":{"$ref":"#/components/servers/broker"}},
                "channels":{"events":{"$ref":"#/components/channels/events"},
                  "other":{"address":"other","servers":[{"$ref":"#/servers/broker"}]}},
                "components":{"servers":{"broker":{"host":"localhost","protocol":"kafka"}},
                  "channels":{"events":{"address":"events"}}},
                "operations":{"send":{"action":"send","channel":{"$ref":"#/channels/events"}}}
                """);
        assertTrue(result.successful(), result.diagnostics()::toString);
        var document = result.document().orElseThrow();
        assertEquals("#/channels/events", document.source().at("/operations/send/channel/$ref").asText());
        Channel channel = document.references().resolve("/operations/send/channel", Channel.class).orElseThrow();
        assertEquals("events", channel.getAddress());
        Server server = document.references().resolve("/channels/other/servers/0", Server.class).orElseThrow();
        assertEquals("localhost", server.getHost());
    }

    @Test
    void encodedPointerTokensAreCheckedAfterUriDecoding() {
        var result = parse("""
                "channels":{"events":{"address":"events"}},
                "operations":{"send":{"action":"send","channel":{"$ref":"#/channels/%65vents"}}}
                """);
        assertTrue(result.successful(), result.diagnostics()::toString);
        var invalid = parse("""
                "components":{"channels":{"events":{"address":"events"}}},
                "operations":{"send":{"action":"send","channel":{"$ref":"#/%63omponents/channels/events"}}}
                """);
        rejected(invalid, "operation.channel", "/operations/send/channel/$ref");
    }
}
