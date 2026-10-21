package io.github.sjacek.asyncwright.parser;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaException;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.regex.JoniRegularExpressionFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the pinned schema and validator before introducing the parser API. */
class SchemaDependencyTest {
    private static final String SCHEMA_ID = "http://asyncapi.com/definitions/3.0.0/asyncapi.json";
    private static final String RESOURCE = "/schemas/asyncapi/3.0.0.json";
    private static final String VALID_DOCUMENT =
            "{\"asyncapi\":\"3.0.0\",\"info\":{\"title\":\"Example\",\"version\":\"1.0.0\"}}";

    private static SchemaRegistry registry() {
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7, builder -> builder
                .schemaRegistryConfig(SchemaRegistryConfig.builder()
                        .locale(Locale.ENGLISH)
                        .formatAssertionsEnabled(true)
                        .regularExpressionFactory(JoniRegularExpressionFactory.getInstance())
                        .build())
                .schemaIdResolvers(resolvers -> resolvers.mappings(
                        Map.of(SCHEMA_ID, "classpath:" + RESOURCE.substring(1))))
                .schemaLoader(loader -> loader.fetchRemoteResources(false)));
    }

    private static Schema officialSchema() {
        Schema schema = registry().getSchema(SchemaLocation.of(SCHEMA_ID));
        schema.initializeValidators();
        return schema;
    }

    @Test
    void bundledSchemaMatchesThePinnedUpstreamBytes() throws IOException, NoSuchAlgorithmException {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream(RESOURCE))) {
            byte[] bytes = input.readAllBytes();
            assertEquals("d4571a420e6ffb7fcc7066c95a6db1202f299a3c51daa103d0706bf30f95e626",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("draft-07/schema"));
        }
    }

    @Test
    void loadsAllBundledSchemaReferencesAndAcceptsAMinimalDocument() {
        assertTrue(officialSchema().validate(VALID_DOCUMENT, InputFormat.JSON).isEmpty());
    }

    @Test
    void rejectsInvalidFieldsWithJsonPointerLocations() {
        var errors = officialSchema().validate(
                "{\"asyncapi\":\"3.0.0\",\"info\":{\"title\":23,\"version\":\"1.0.0\"}}",
                InputFormat.JSON);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(error ->
                "/info/title".equals(error.getInstanceLocation().toString())
                        && "type".equals(error.getKeyword())), errors::toString);
    }

    @Test
    void cannotFetchAnUnregisteredRemoteSchema() {
        var exception = assertThrows(SchemaException.class, () -> registry().getSchema(
                SchemaLocation.of("https://unregistered.invalid/schema.json")));
        assertInstanceOf(FileNotFoundException.class, exception.getCause());
    }

    @Test
    void nullableKeywordPassesDocumentValidationButDoesNotMakeAStringNullable() {
        String document = """
                {"asyncapi":"3.0.0","info":{"title":"Example","version":"1.0.0"},
                 "components":{"schemas":{"Value":{"type":"string","nullable":true}}}}
                """;
        assertTrue(officialSchema().validate(document, InputFormat.JSON).isEmpty());
        Schema payload = registry().getSchema("{\"type\":\"string\",\"nullable\":true}");
        assertFalse(payload.validate("null", InputFormat.JSON).isEmpty());
        assertTrue(payload.validate("\"value\"", InputFormat.JSON).isEmpty());
        Schema optionalProperty = registry().getSchema(
                "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\",\"nullable\":true}}}");
        assertTrue(optionalProperty.validate("{}", InputFormat.JSON).isEmpty());
        assertFalse(optionalProperty.validate("{\"value\":null}", InputFormat.JSON).isEmpty());
    }

    @Test
    void nullTypeUnionPermitsNullAndStringsWhilePreservingOtherConstraints() {
        Schema payload = registry().getSchema(
                "{\"type\":[\"string\",\"null\"],\"format\":\"uuid\"}");
        assertTrue(payload.validate("null", InputFormat.JSON).isEmpty());
        assertTrue(payload.validate("\"e2eec801-7110-4350-8e75-1f40c7429111\"", InputFormat.JSON).isEmpty());
        assertFalse(payload.validate("42", InputFormat.JSON).isEmpty());
        assertFalse(payload.validate("\"not-a-uuid\"", InputFormat.JSON).isEmpty());
    }
}
