package io.github.sjacek.asyncwright.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sjacek.asyncwright.api.AsyncApi30;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

/** Pins the complete correction allowlist; unrelated oneOf constraints remain exclusive. */
class SchemaCompatibilityTest {
    @Test
    void correctsExactlyNineOverlappingAlternativesAndTheExistingBooleanCondition() throws Exception {
        var mapper = new ObjectMapper();
        ObjectNode original;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream(AsyncApi30.SCHEMA_RESOURCE))) {
            original = (ObjectNode) mapper.readTree(input);
        }
        var corrected = SchemaCompatibility.schema();
        var restored = corrected.deepCopy();
        for (String address : List.of("kafka/0.5.0/message/key", "websockets/0.1.0/channel/query",
                "websockets/0.1.0/channel/headers", "anypointmq/0.0.1/message/headers",
                "mqtt/0.2.0/server/sessionExpiryInterval", "mqtt/0.2.0/server/maximumPacketSize",
                "mqtt/0.2.0/operation/messageExpiryInterval", "mqtt/0.2.0/message/correlationData",
                "mqtt/0.2.0/message/responseTopic")) {
            String[] parts = address.split("/");
            String definition = "http://asyncapi.com/bindings/" + parts[0] + "/" + parts[1] + "/" + parts[2] + ".json";
            var field = (ObjectNode) restored.path("definitions").path(definition).path("properties").path(parts[3]);
            assertFalse(field.has("oneOf"), address);
            assertEquals(original.path("definitions").path(definition).path("properties").path(parts[3]).path("oneOf"),
                    field.path("anyOf"), address);
            field.set("oneOf", field.remove("anyOf"));
        }
        ((ObjectNode) restored.path("definitions").path(AsyncApi30.ANY_SCHEMA_DEFINITION).path("if")).remove("type");
        assertEquals(original, restored, "No other schema keyword, branch or scalar bound may change");
        assertEquals(corrected, SchemaCompatibility.schema(), "Each caller receives a fresh corrected tree");
    }

    /** Cached callers must never share a mutable adaptation tree, including nested branch arrays. */
    @Test
    void mutatingOneAdaptationCannotChangeTheNextCallOrTheBundledResource() throws Exception {
        var first = SchemaCompatibility.schema();
        var expected = first.deepCopy();
        var definitions = (ObjectNode) first.path("definitions");
        ((ObjectNode) definitions.path(AsyncApi30.ANY_SCHEMA_DEFINITION).path("if")).put("type", "array");
        ((ObjectNode) definitions.path("http://asyncapi.com/bindings/kafka/0.5.0/message.json")
                .path("properties").path("key")).removeAll();
        assertNotEquals(expected, first);
        assertEquals(expected, SchemaCompatibility.schema());
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream(AsyncApi30.SCHEMA_RESOURCE))) {
            var original = new ObjectMapper().readTree(input);
            assertTrue(original.path("definitions").path("http://asyncapi.com/bindings/kafka/0.5.0/message.json")
                    .path("properties").path("key").has("oneOf"));
            assertFalse(original.path("definitions").path(AsyncApi30.ANY_SCHEMA_DEFINITION).path("if").has("type"));
        }
    }
}
