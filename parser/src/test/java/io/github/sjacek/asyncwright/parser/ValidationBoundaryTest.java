package io.github.sjacek.asyncwright.parser;

import com.asyncapi.schemas.asyncapi.AsyncAPISchema;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sjacek.asyncwright.api.Diagnostic;
import io.github.sjacek.asyncwright.api.Severity;
import io.github.sjacek.asyncwright.api.ExtensionSpec;
import io.github.sjacek.asyncwright.api.SourceLocation;
import io.github.sjacek.asyncwright.api.AsyncApiRule;
import io.github.sjacek.asyncwright.api.ExtensionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P3 capability boundaries and P5 definition/use and stage contracts. */
class ValidationBoundaryTest {
    private final AsyncApiParser parser = new AsyncApiParser();

    private ParseResult parse(String body) {
        return parser.parse("specs/events.json", "{\"asyncapi\":\"3.0.0\",\"info\":{\"title\":\"Events\",\"version\":\"1.0.0\"}," + body + "}");
    }

    private static Diagnostic onlyError(ParseResult result, String rule, String pointer) {
        assertFalse(result.successful());
        assertTrue(result.document().isEmpty());
        var errors = result.diagnostics().stream().filter(d -> d.severity() == Severity.ERROR).toList();
        assertEquals(1, errors.size(), result.diagnostics()::toString);
        assertEquals(rule, errors.get(0).ruleId());
        assertEquals(pointer, errors.get(0).pointer());
        return errors.get(0);
    }

    @Test
    void missingChannelParameterIsReportedOnceAtTheDefinitionWithTransitiveUses() {
        var result = parse("""
                "channels":{"one":{"$ref":"#/components/channels/shared"},"two":{"$ref":"#/components/channels/shared"}},
                "components":{"channels":{"shared":{"address":"events/{tenant}"}}},
                "operations":{"send":{"action":"send","channel":{"$ref":"#/channels/one"}}}
                """);
        var diagnostic = onlyError(result, "channel.parameter", "/components/channels/shared/address");
        assertEquals(List.of("/channels/one/$ref", "/channels/two/$ref", "/operations/send/channel/$ref"),
                diagnostic.related().stream().map(SourceLocation::pointer).toList());
    }

    @Test
    void unsupportedSharedDefinitionIncludesItsReferenceUses() {
        var result = parse("""
                "channels":{"events":{"address":"events","messages":{
                  "one":{"$ref":"#/components/messages/shared"},"two":{"$ref":"#/components/messages/shared"}}}},
                "components":{"messages":{"shared":{"traits":[{"summary":"Trait"}]}}}
                """);
        var diagnostic = onlyError(result, "unsupported.construct", "/components/messages/shared/traits");
        assertEquals(List.of("/channels/events/messages/one/$ref", "/channels/events/messages/two/$ref"),
                diagnostic.related().stream().map(SourceLocation::pointer).toList());
    }

    @Test
    void unresolvedChannelMessageSuppressesMembershipErrorsButNotIndependentChannelErrors() {
        var result = parse("""
                "channels":{"events":{"address":"events","messages":{"broken":{"$ref":"#/components/messages/missing"}}},
                  "other":{"address":"other/{tenant}"}},
                "components":{"messages":{"m":{"payload":{"type":"string"}}}},
                "operations":{"send":{"action":"send","channel":{"$ref":"#/channels/events"},
                  "messages":[{"$ref":"#/components/messages/m"}]}}
                """);
        assertFalse(result.successful());
        assertTrue(result.document().isEmpty());
        assertEquals(List.of("reference.missing", "channel.parameter"), result.diagnostics().stream().map(Diagnostic::ruleId).toList());
    }

    @Test
    void anUnresolvedOperationMessageDoesNotSuppressOtherIndependentMembershipErrors() {
        var result = parse("""
                "channels":{"events":{"address":"events","messages":{}}},
                "components":{"messages":{"m":{"payload":{"type":"string"}}}},
                "operations":{"send":{"action":"send","channel":{"$ref":"#/channels/events"},
                  "messages":[{"$ref":"#/components/messages/missing"},{"$ref":"#/components/messages/m"}]}}
                """);
        assertEquals(List.of("reference.missing", "operation.message"), result.diagnostics().stream().map(Diagnostic::ruleId).toList());
    }

    @Test
    void yamlAliasesHaveACapabilityDiagnosticAtTheAliasPosition() {
        var result = parser.parse("specs/events.yaml", """
                asyncapi: 3.0.0
                info:
                  title: &name Events
                  version: *name
                """);
        var diagnostic = onlyError(result, "unsupported.yaml-alias", "/info/version");
        assertEquals(4, diagnostic.line());
        assertEquals(12, diagnostic.column());
    }

    @Test
    void additionalYamlDocumentsPointToTheSecondDocumentRatherThanTheStartOfTheFile() {
        var result = parser.parse("specs/events.yaml", """
                asyncapi: 3.0.0
                info: {title: Events, version: 1.0.0}
                ---
                asyncapi: 3.0.0
                """);
        var diagnostic = onlyError(result, "syntax", "");
        assertEquals(4, diagnostic.line());
        assertEquals(1, diagnostic.column());
    }

    @Test
    void unsupportedPayloadDialectCannotBeSilentlyTreatedAsDraftSeven() {
        onlyError(parse("""
                "components":{"schemas":{"Value":{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"string"}}}
                """), "unsupported.schema-dialect", "/components/schemas/Value/$schema");
    }

    @Test
    void schemaResourceIdsCannotSilentlyChangeTheReferenceBase() {
        onlyError(parse("""
                "components":{"schemas":{"Value":{"$id":"other.json","type":"object",
                  "properties":{"child":{"$ref":"#/components/schemas/Text"}}},"Text":{"type":"string"}}}
                """), "unsupported.schema-id", "/components/schemas/Value/$id");
    }

    @Test
    void newerAvroFormatsInComponentSchemasCannotBypassTheCapabilityCheck() {
        onlyError(parse("""
                "components":{"schemas":{"Value":{"schemaFormat":"application/vnd.apache.avro+json;version=1.10.0",
                  "schema":{"type":"record","name":"Value","fields":[{"name":"text","type":"string"}]}}}}
                """), "unsupported.construct", "/components/schemas/Value/schemaFormat");
    }

    @Test
    void defaultFormatComponentWrappersIndexTheirNestedSchemaReferences() {
        var result = parse("""
                "components":{"schemas":{"Value":{"schemaFormat":"application/vnd.aai.asyncapi+json;version=3.0.0",
                  "schema":{"type":"object","properties":{"text":{"$ref":"#/components/schemas/Missing"}}}}}}
                """);
        onlyError(result, "reference.missing", "/components/schemas/Value/schema/properties/text/$ref");
    }

    @Test
    void everyDefaultFormatWorksInComponentsAndThroughPayloadReferences() {
        for (String format : List.of("application/vnd.aai.asyncapi;version=3.0.0",
                "application/vnd.aai.asyncapi+json;version=3.0.0", "application/vnd.aai.asyncapi+yaml;version=3.0.0")) {
            var result = parse("\"components\":{\"schemas\":{\"Value\":{\"schemaFormat\":\"" + format
                    + "\",\"schema\":{\"type\":\"object\",\"properties\":{\"text\":{\"$ref\":\"#/components/schemas/Text\"}}}},"
                    + "\"Text\":{\"type\":\"string\"}},\"messages\":{\"m\":{\"payload\":{\"$ref\":\"#/components/schemas/Value\"}}}} ");
            assertTrue(result.successful(), result.diagnostics()::toString);
            assertTrue(result.document().orElseThrow().references().resolve("/components/schemas/Value/schema/properties/text").isPresent());
        }
    }

    @Test
    void declaredDraftSevenAndReferenceLikeInstanceDataRemainSupported() {
        var result = parse("""
                "components":{"schemas":{"Value":{"$schema":"http://json-schema.org/draft-07/schema#",
                  "type":"object","examples":[{"$id":"data.json","$schema":"arbitrary","traits":[{}]}],
                  "x-data":{"$id":"data.json","$schema":"arbitrary"}}}}
                """);
        assertTrue(result.successful(), result.diagnostics()::toString);
    }

    @Test
    void customBindingsRemainAnExplicitModelLimitWithNoPartialModelAndWithRelatedUses() {
        var diagnostic = onlyError(parse("""
                "channels":{"one":{"$ref":"#/components/channels/shared"},"two":{"$ref":"#/components/channels/shared"}},
                "components":{"channels":{"shared":{"address":"events","bindings":{"x-custom":{"enabled":true}}}}}
                """), "model.preservation", "/components/channels/shared/bindings/x-custom");
        assertEquals(2, diagnostic.related().size());
    }

    @Test
    void structuralErrorsSuppressUnsupportedReferenceAndProjectStages() throws Exception {
        var result = parse("""
                "components":{"messages":{"m":{"traits":[{"summary":"Trait"}]}}},
                "operations":{"send":{"action":"invalid","channel":{"$ref":"#/channels/missing"}}}
                """);
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().allMatch(d -> d.ruleId().startsWith("schema.")));
        assertEquals(result.diagnostics(), new ExtensionExecutor().validate(result,
                List.of(new ExtensionSpec("missing.Rule", Map.of()))).diagnostics());
    }

    @Test
    void jsonInstanceValuesArePreservedByTheParserTypedResolverAndExtensionSnapshots() throws Exception {
        String value = "{\"traits\":[{\"arbitrary\":true}],\"$ref\":\"external.json\","
                + "\"values\":[null,true,[\"text\",1]],\"large\":123456789012345678901234567890,\"price\":0.12345678901234567890123456789}";
        var result = parse("\"components\":{\"schemas\":{\"Value\":{\"type\":\"object\",\"const\":" + value
                + ",\"default\":" + value + ",\"examples\":[" + value + "]}}}");
        assertTrue(result.successful(), result.diagnostics()::toString);
        var document = result.document().orElseThrow();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode original = document.source().at("/components/schemas/Value");
        JsonNode model = mapper.valueToTree(document.model());
        assertEquals(original.path("const"), model.at("/components/schemas/Value/const"));
        assertEquals(original.path("default"), model.at("/components/schemas/Value/default"));
        assertEquals(original.path("examples"), model.at("/components/schemas/Value/examples"));
        AsyncAPISchema typed = document.references().resolve("/components/schemas/Value", AsyncAPISchema.class).orElseThrow();
        assertEquals(original.path("examples"), mapper.valueToTree(typed).path("examples"));
        assertTrue(new ExtensionExecutor().validate(result, List.of(new ExtensionSpec(InstanceRule.class.getName(),
                Map.of("expected", mapper.writeValueAsString(original))))).successful());
    }

    public static class InstanceRule implements AsyncApiRule {
        @Override
        public List<Diagnostic> validate(ExtensionContext context) throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode model = mapper.valueToTree(context.document().model());
            JsonNode expected = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                    .readTree(context.requireParameter("expected"));
            for (String field : List.of("const", "default", "examples"))
                assertEquals(expected.path(field), model.at("/components/schemas/Value/" + field));
            return List.of();
        }
    }

    @Test
    void diagnosticJsonHasTheP5FieldsAndTiesDoNotDependOnInputOrder() throws Exception {
        var a = new Diagnostic("rule", Severity.ERROR, "message", "specs/events.yaml", 2, 3, "/a", List.of());
        var b = new Diagnostic("rule", Severity.WARNING, "message", "specs/events.yaml", 2, 3, "/a", List.of());
        var c = new Diagnostic("rule", Severity.ERROR, "message", "specs/events.yaml", 2, 3, "/b",
                List.of(new SourceLocation("specs/events.yaml", 4, 5, "/use/$ref")));
        var first = new ParseResult(java.util.Optional.empty(), List.of(c, a, b, a));
        var second = new ParseResult(java.util.Optional.empty(), List.of(b, a, c));
        assertEquals(first.diagnosticsJson(), second.diagnosticsJson());
        JsonNode json = new ObjectMapper().readTree(first.diagnosticsJson());
        assertEquals(3, json.size());
        java.util.Set<String> fields = new java.util.HashSet<>();
        json.get(2).fieldNames().forEachRemaining(fields::add);
        assertEquals(java.util.Set.of("ruleId", "severity", "message", "file", "line", "column", "pointer", "related"), fields);
        assertEquals("error", json.get(2).path("severity").asText());
        assertEquals("/use/$ref", json.get(2).path("related").get(0).path("pointer").asText());
    }

    @Test
    void diagnosticFilesRejectWindowsAbsoluteDriveRelativeAndParentPathsOnEveryHost() {
        for (String file : List.of("C:events.yaml", "C:/events.yaml", "/events.yaml", "../events.yaml", "\\\\host\\share\\events.yaml"))
            assertThrows(IllegalArgumentException.class, () -> parser.parse(file, "asyncapi: 3.0.0"), file);
    }
}
