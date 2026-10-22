package io.github.sjacek.asyncwright.parser;

import io.github.sjacek.asyncwright.api.AsyncApi30;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.dialect.Dialect;
import com.networknt.schema.dialect.Dialects;
import com.networknt.schema.keyword.AnnotationKeyword;

import java.io.IOException;
import java.util.Objects;

/** Applies a narrow runtime correction; the pinned upstream resource stays byte-identical. */
final class SchemaCompatibility {
    private SchemaCompatibility() { }

    static Dialect dialect() {
        // The bundled AsyncAPI schema uses singular example as metadata on extension definitions.
        // Match NetworkNT's existing annotation semantics without its unknown-keyword warning.
        return Dialect.builder(Dialects.getDraft7()).keyword(new AnnotationKeyword(EXAMPLE)).build();
    }

    static JsonNode schema() {
        try (var input = Objects.requireNonNull(SchemaCompatibility.class.getResourceAsStream(AsyncApi30.SCHEMA_RESOURCE))) {
            JsonNode schema = new ObjectMapper().readTree(input);
            // required alone also matches non-objects, incorrectly routing booleans to Multi Format Schema.
            ObjectNode condition = (ObjectNode) schema.path(DEFINITIONS)
                    .path(AsyncApi30.ANY_SCHEMA_DEFINITION).path(IF);
            condition.put(TYPE, "object");
            return schema;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load the bundled AsyncAPI schema", exception);
        }
    }
}
