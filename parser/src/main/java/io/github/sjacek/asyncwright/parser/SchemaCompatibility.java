package io.github.sjacek.asyncwright.parser;

import io.github.sjacek.asyncwright.api.AsyncApi30;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Objects;

/** Applies a narrow runtime correction; the pinned upstream resource stays byte-identical. */
final class SchemaCompatibility {
    private SchemaCompatibility() { }

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
