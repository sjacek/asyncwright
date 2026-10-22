package io.github.sjacek.asyncwright.gradle;

import io.github.sjacek.asyncwright.api.*;
import io.github.sjacek.asyncwright.parser.AsyncApiParser;
import io.github.sjacek.asyncwright.parser.ExtensionExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

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
