package io.github.sjacek.asyncwright.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Objects;

/** Applies a narrow runtime correction; the pinned upstream resource stays byte-identical. */
final class SchemaCompatibility {
    private SchemaCompatibility() { }

    static JsonNode schema() {
        try (var input = Objects.requireNonNull(SchemaCompatibility.class.getResourceAsStream("/schemas/asyncapi/3.0.0.json"))) {
            JsonNode schema = new ObjectMapper().readTree(input);
            // required alone also matches non-objects, incorrectly routing booleans to Multi Format Schema.
            ObjectNode condition = (ObjectNode) schema.path("definitions")
                    .path("http://asyncapi.com/definitions/3.0.0/anySchema.json").path("if");
            condition.put("type", "object");
            return schema;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load the bundled AsyncAPI schema", exception);
        }
    }
}
