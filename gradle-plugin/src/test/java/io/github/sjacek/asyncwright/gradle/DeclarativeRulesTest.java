package io.github.sjacek.asyncwright.gradle;

import io.github.sjacek.asyncwright.api.*;
import io.github.sjacek.asyncwright.parser.AsyncApiParser;
import io.github.sjacek.asyncwright.parser.ExtensionExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import static io.github.sjacek.asyncwright.gradle.DeclarativeRules.Parameter.*;
import static org.junit.jupiter.api.Assertions.*;

class DeclarativeRulesTest {
    @ParameterizedTest
    @CsvSource({"events.*,0,true", "EVENTS.*,0,false", "EVENTS.*,2,true", "events,0,false", "created,0,false"})
    void addressesUseFullMatchesAndSerializedPatternFlags(String regex, int flags, boolean accepted) throws Exception {
        var result = validate("""
                channels:
                  events: {address: events.created}
                """, regex, flags, "", "");
        assertEquals(accepted, result.successful(), result.diagnostics()::toString);
        if (!accepted) assertEquals(BuiltinRuleId.PROJECT_CHANNEL_ADDRESS.id(), result.diagnostics().get(0).ruleId());
    }

    @Test
    void repeatedReferencesProduceOneDefinitionFindingWithEveryUsageLocation() throws Exception {
        var result = validate("""
                channels:
                  first: {$ref: '#/components/channels/shared'}
                  second: {$ref: '#/components/channels/shared'}
                components:
                  channels:
                    shared: {address: wrong}
                """, "events[.].+", 0, "", "");
        assertEquals(1, result.diagnostics().size());
        var finding = result.diagnostics().get(0);
        assertEquals("/components/channels/shared/address", finding.pointer());
        assertEquals(List.of("/channels/first/$ref", "/channels/second/$ref"),
                finding.related().stream().map(SourceLocation::pointer).toList());
    }

    @Test
    void aMissingAddressIsReportedAtTheChannelWhileRetainingThePropertyPointer() throws Exception {
        var result = validate("channels: {events: {}}\n", "events.*", 0, "", "");
        assertEquals(1, result.diagnostics().size());
        assertEquals("/channels/events/address", result.diagnostics().get(0).pointer());
        assertTrue(result.diagnostics().get(0).line() > 1);
    }

    @Test
    void disabledRulesPermitAbsentFieldsAndProtocolDoesNotRequireServersToExist() throws Exception {
        assertTrue(validate("channels: {events: {}}\n", "", 0, "", "").successful());
        assertTrue(validate("", "", 0, "", "amqp").successful());
        var missingType = validate("", "", 0, "application/json", "");
        assertEquals(BuiltinRuleId.PROJECT_DEFAULT_CONTENT_TYPE.id(), missingType.diagnostics().get(0).ruleId());
        assertEquals("/defaultContentType", missingType.diagnostics().get(0).pointer());
    }

    @Test
    void malformedSerializedPatternsAreExecutionFailuresRatherThanContractFindings() {
        var failure = assertThrows(ExtensionExecutionException.class, () -> validate("", "[", 0, "", ""));
        assertInstanceOf(java.util.regex.PatternSyntaxException.class, failure.getCause());
        var flags = assertThrows(ExtensionExecutionException.class,
                () -> validate("", "events", Integer.MIN_VALUE, "", ""));
        assertInstanceOf(IllegalArgumentException.class, flags.getCause());
    }

    @Test
    void protocolListsAllowBothKafkaTransportsAndLocateRejectedResolvedServers() throws Exception {
        String body = """
                servers:
                  public: {host: localhost, protocol: kafka}
                  secure: {$ref: '#/components/servers/secure'}
                components:
                  servers:
                    secure: {host: localhost, protocol: kafka-secure}
                """;
        var result = validateProtocols(body, "[\"kafka\",\"kafka-secure\"]");
        assertTrue(result.successful(), result.diagnostics()::toString);
        var rejected = validateProtocols(body, "[\"kafka\"]");
        assertEquals(1, rejected.diagnostics().size());
        var finding = rejected.diagnostics().get(0);
        assertEquals(BuiltinRuleId.PROJECT_PROTOCOL.id(), finding.ruleId());
        assertEquals("/components/servers/secure/protocol", finding.pointer());
        assertEquals("/servers/secure/$ref", finding.related().get(0).pointer());
        assertTrue(validateProtocols(body, "[]").successful());
        assertTrue(validateProtocols("", "[\"kafka\"]").successful());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[1]", "[null]", "[\"\"]", "[\" \"]", "[",
            "", "  ", "false", "\"kafka\"", "[[]]", "[\"kafka\"] true", "[\"kafka\"] {}"})
    void malformedProtocolListsAreExecutionFailures(String encoded) {
        var failure = assertThrows(ExtensionExecutionException.class, () -> validateProtocols("", encoded));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    /** List presence, not its contents, decides precedence over old ExtensionSpec parameters. */
    @Test
    void explicitProtocolListsOverrideLegacyParametersAndPreserveExactWireNames() throws Exception {
        String body = "servers: {broker: {host: localhost, protocol: 'custom,transport'}}\n";
        assertTrue(validateProtocols(body, "[\"custom,transport\",\"kafka\",\"custom,transport\"]", "amqp").successful());
        assertTrue(validateProtocols(body, "[]", "amqp").successful());
        for (String encoded : List.of("[\"CUSTOM,TRANSPORT\"]", "[\" custom,transport \"]")) {
            var rejected = validateProtocols(body, encoded, "");
            assertEquals(1, rejected.diagnostics().size());
            assertEquals(BuiltinRuleId.PROJECT_PROTOCOL.id(), rejected.diagnostics().get(0).ruleId());
        }
    }

    /** Repeated root aliases should not multiply one definition's protocol violation. */
    @Test
    void protocolFindingsDeduplicateDefinitionsAndKeepEveryRelatedAlias() throws Exception {
        var result = validateProtocols("""
                servers:
                  first: {$ref: '#/components/servers/shared'}
                  second: {$ref: '#/components/servers/shared'}
                components:
                  servers:
                    shared: {host: localhost, protocol: amqp}
                """, "[\"kafka\",\"kafka-secure\",\"kafka\"]");
        assertEquals(1, result.diagnostics().size());
        var finding = result.diagnostics().get(0);
        assertEquals("/components/servers/shared/protocol", finding.pointer());
        assertEquals(List.of("/servers/first/$ref", "/servers/second/$ref"),
                finding.related().stream().map(SourceLocation::pointer).toList());
    }

    private static ExtensionResult validateProtocols(String body, String encoded) throws Exception {
        return validateProtocols(body, encoded, null);
    }

    private static ExtensionResult validateProtocols(String body, String encoded, String legacy) throws Exception {
        var parsed = new AsyncApiParser().parse("contracts/events.yaml",
                "asyncapi: 3.0.0\ninfo: {title: Rules, version: 1.0.0}\n" + body);
        assertTrue(parsed.successful(), parsed.diagnostics()::toString);
        Map<String, String> parameters = new LinkedHashMap<>(Map.of(CHANNEL_ADDRESS.key(), "",
                CHANNEL_ADDRESS_FLAGS.key(), "0", CONTENT_TYPE.key(), "", PROTOCOLS.key(), encoded));
        if (legacy != null) parameters.put(PROTOCOL.key(), legacy);
        return new ExtensionExecutor().validate(parsed, List.of(new ExtensionSpec(DeclarativeRules.class.getName(), parameters)));
    }

    private static ExtensionResult validate(String body, String regex, int flags, String contentType, String protocol)
            throws ExtensionExecutionException {
        var parsed = new AsyncApiParser().parse("contracts/events.yaml",
                "asyncapi: 3.0.0\ninfo: {title: Rules, version: 1.0.0}\n" + body);
        assertTrue(parsed.successful(), parsed.diagnostics()::toString);
        return new ExtensionExecutor().validate(parsed, List.of(new ExtensionSpec(DeclarativeRules.class.getName(), Map.of(
                CHANNEL_ADDRESS.key(), regex, CHANNEL_ADDRESS_FLAGS.key(), Integer.toString(flags),
                CONTENT_TYPE.key(), contentType, PROTOCOL.key(), protocol))));
    }
}
