package io.github.sjacek.asyncwright.parser;

import io.github.sjacek.asyncwright.api.BuiltinRuleId;
import io.github.sjacek.asyncwright.api.SourceLocation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Names apply to reusable components, not payload property names or extension data. */
class ComponentNamesTest {
    @ParameterizedTest
    @ValueSource(strings = {"schemas", "servers", "channels", "operations", "messages", "securitySchemes",
            "parameters", "correlationIds", "tags", "serverBindings", "channelBindings", "operationBindings", "messageBindings",
            "operationTraits", "messageTraits"})
    void locatesEveryInvalidComponentNameAtItsKey(String collection) {
        String value = switch (collection) {
            case "servers" -> "{host: localhost, protocol: kafka}";
            case "operations" -> "{action: send, channel: {$ref: '#/channels/events'}}";
            case "securitySchemes" -> "{type: userPassword}";
            case "correlationIds" -> "{location: '$message.header#/id'}";
            case "tags" -> "{name: sample}";
            default -> "{}";
        };
        for (String name : new String[]{"bad name", "bad/name", "bad~name", "bad+name", "bad%name", "", "zażółć"}) {
            String input = "asyncapi: 3.0.0\ninfo: {title: Names, version: 1.0.0}\nchannels: {events: {}}\ncomponents:\n  "
                    + collection + ":\n    '" + name + "': " + value + "\n";
            var result = new AsyncApiParser().parse("components.yaml", input);
            assertTrue(result.document().isEmpty(), name);
            var diagnostic = result.diagnostics().stream().filter(d -> d.ruleId().equals(BuiltinRuleId.COMPONENT_NAME.id()))
                    .findFirst().orElseThrow(() -> new AssertionError(result.diagnostics()));
            assertEquals(6, diagnostic.line());
            assertEquals(5, diagnostic.column());
            assertEquals("/components/" + collection + "/" + SourceDocument.escape(name), diagnostic.pointer());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "0", "A.b_9-z", ".", "_", "-"})
    void validNamesAndArbitrarySchemaPropertyNamesArePreserved(String name) {
        var result = new AsyncApiParser().parse("names.yaml", """
                asyncapi: 3.0.0
                info: {title: Names, version: 1.0.0}
                components:
                  schemas:
                    '%s':
                      type: object
                      properties:
                        'a/b~ c+d': {type: string}
                  x-data: {'bad/name': {$ref: remote.json}}
                """.formatted(name));
        assertTrue(result.successful(), result.diagnostics()::toString);
    }

    /** A name error is attached to the escaped definition key and retains both reference-use locations. */
    @Test
    void invalidDefinitionNamesRetainRelatedUsagesThroughAliases() {
        var result = new AsyncApiParser().parse("names.yaml", """
                asyncapi: 3.0.0
                info: {title: Shared invalid component, version: 1.0.0}
                components:
                  schemas:
                    'Bad/name': {type: string}
                    Alias: {$ref: '#/components/schemas/Bad~1name'}
                  messages:
                    First: {payload: {$ref: '#/components/schemas/Alias'}}
                    Second: {payload: {$ref: '#/components/schemas/Bad~1name'}}
                """);
        assertTrue(result.document().isEmpty());
        assertEquals(1, result.diagnostics().size());
        var error = result.diagnostics().get(0);
        assertEquals(BuiltinRuleId.COMPONENT_NAME.id(), error.ruleId());
        assertEquals("/components/schemas/Bad~1name", error.pointer());
        assertEquals(5, error.line());
        assertEquals(5, error.column());
        assertEquals(List.of("/components/schemas/Alias/$ref", "/components/messages/First/payload/$ref",
                "/components/messages/Second/payload/$ref"), error.related().stream().map(SourceLocation::pointer).toList());
    }
}
