package io.github.sjacek.asyncwright.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchemaMetadataTest {
    private final AsyncApiParser parser = new AsyncApiParser();

    @Test
    void exampleAnnotationsDoNotRestrictExtensionValues() {
        var result = parser.parse("events.json", """
                {"asyncapi":"3.0.0","info":{"title":"Events","version":"1.0.0",
                 "x-x":"another-team","x-linkedin":"https://www.linkedin.com/company/another-team/"}}
                """);
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertTrue(result.diagnostics().isEmpty());
        var document = result.document().orElseThrow();
        assertEquals("another-team", document.source().at("/info/x-x").asText());
        assertEquals("https://www.linkedin.com/company/another-team/",
                document.source().at("/info/x-linkedin").asText());
    }

    @Test
    void annotationRegistrationPreservesTypePatternAndUnknownPropertyValidation() {
        var result = parser.parse("events.json", """
                {"asyncapi":"3.0.0","info":{"title":"Events","version":"1.0.0",
                 "x-x":42,"x-linkedin":"https://example.org/team","example":"unexpected field"}}
                """);
        assertFalse(result.successful());
        assertTrue(result.document().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                d.ruleId().equals("schema.type") && d.pointer().equals("/info/x-x")), result.diagnostics()::toString);
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                d.ruleId().equals("schema.pattern") && d.pointer().equals("/info/x-linkedin")), result.diagnostics()::toString);
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                d.ruleId().equals("schema.additionalProperties")), result.diagnostics()::toString);
    }
}
