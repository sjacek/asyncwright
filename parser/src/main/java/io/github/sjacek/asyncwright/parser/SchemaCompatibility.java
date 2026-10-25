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
import java.util.List;

/**
 * Adapts the pinned structural schema without changing its resource bytes or fetching updates.
 * The corrections concern boolean schema routing and overlapping Reference/Schema alternatives;
 * they do not dereference documents or relax target-kind checks in the separate resolver.
 */
final class SchemaCompatibility {
    /** Explicit corrections, not a global relaxation of oneOf validation. */
    private record Alternative(String protocol, String version, String owner, String field) {
        String definition() { return "http://asyncapi.com/bindings/" + protocol + "/" + version + "/" + owner + ".json"; }
    }
    private static final List<Alternative> OVERLAPPING_REFERENCES = List.of(
            new Alternative("kafka", "0.5.0", "message", "key"),
            new Alternative("websockets", "0.1.0", "channel", "query"),
            new Alternative("websockets", "0.1.0", "channel", HEADERS),
            new Alternative("anypointmq", "0.0.1", "message", HEADERS),
            new Alternative("mqtt", "0.2.0", "server", "sessionExpiryInterval"),
            new Alternative("mqtt", "0.2.0", "server", "maximumPacketSize"),
            new Alternative("mqtt", "0.2.0", "operation", "messageExpiryInterval"),
            new Alternative("mqtt", "0.2.0", "message", "correlationData"),
            new Alternative("mqtt", "0.2.0", "message", "responseTopic"));
    private SchemaCompatibility() { }

    /** Accepts the upstream example annotation without disabling unknown-keyword diagnostics globally. */
    static Dialect dialect() {
        // The bundled AsyncAPI schema uses singular example as metadata on extension definitions.
        // Match NetworkNT's existing annotation semantics without its unknown-keyword warning.
        return Dialect.builder(Dialects.getDraft7()).keyword(new AnnotationKeyword(EXAMPLE)).build();
    }

    /**
     * Returns a fresh tree for each caller. A changed correction address fails initialization
     * rather than silently skipping a workaround after an upstream schema upgrade.
     * Exact tree-difference and resource-checksum tests pin the complete allowed transformation.
     */
    static JsonNode schema() {
        try (var input = Objects.requireNonNull(SchemaCompatibility.class.getResourceAsStream(AsyncApi30.SCHEMA_RESOURCE))) {
            JsonNode schema = new ObjectMapper().readTree(input);
            // required alone also matches non-objects, incorrectly routing booleans to Multi Format Schema.
            ObjectNode condition = (ObjectNode) schema.path(DEFINITIONS)
                    .path(AsyncApi30.ANY_SCHEMA_DEFINITION).path(IF);
            condition.put(TYPE, "object");
            // Reference Objects also satisfy the draft-07 schema branch. Retain every
            // alternative (including MQTT scalars), but permit their documented overlap.
            for (Alternative correction : OVERLAPPING_REFERENCES) {
                JsonNode field = schema.path(DEFINITIONS).path(correction.definition())
                        .path(PROPERTIES).path(correction.field());
                if (!(field instanceof ObjectNode object) || !object.path(ONE_OF).isArray()
                        || object.has(ANY_OF))
                    throw new IllegalStateException("Pinned binding schema changed: " + correction);
                object.set(ANY_OF, object.remove(ONE_OF));
            }
            return schema;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load the bundled AsyncAPI schema", exception);
        }
    }
}
