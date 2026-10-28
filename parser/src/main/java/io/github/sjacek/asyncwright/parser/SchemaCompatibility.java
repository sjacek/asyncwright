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
import java.io.InputStream;
import java.util.Objects;
import java.util.List;

/**
 * Adapts the pinned structural schema without changing its resource bytes or fetching updates.
 * The corrections concern boolean schema routing and overlapping Reference/Schema alternatives;
 * they do not dereference documents or relax target-kind checks in the separate resolver.
 * <p>Each read owns its resource connection. Gradle workers can initialize this class in
 * separate classloaders whose lifetimes overlap, including during another worker's cleanup.
 */
final class SchemaCompatibility {
    /** Explicit corrections, not a global relaxation of oneOf validation. */
    private record Alternative(String protocol, String version, String owner, String field) {
        /** Returns a definition key in the bundled tree; the HTTP-shaped identifier is never fetched. */
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
     * <p>The resource is fully read before the tree escapes this method. Both parse failures
     * and failed correction checks release the owned stream through try-with-resources.
     *
     * @return an independently mutable, corrected structural schema
     * @throws IllegalStateException if reading fails or the pinned correction positions have changed
     * @throws NullPointerException if the bundled schema resource is missing from the classpath
     */
    static JsonNode schema() {
        try (var input = schemaStream()) {
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

    /**
     * Owns this read independently of other isolated workers. getResourceAsStream can share a
     * cached JarFile whose streams are closed when a different URLClassLoader is closed.
     * Disable caching only for this connection, before opening it; do not change JVM defaults.
     * The caller closes the private stream after parsing, including on an adaptation failure.
     * <p>Resolve the URL first so its cache policy can be configured before a stream exists.
     * For the standard JDK JAR handler, closing this uncached stream also releases its private
     * JarFile. Directory-based development classpaths use the same ownership contract.
     *
     * @return the open schema stream, which the caller must close
     * @throws IOException if the resource connection cannot be opened
     * @throws NullPointerException if the schema was omitted from the packaged resources
     */
    private static InputStream schemaStream() throws IOException {
        var resource = Objects.requireNonNull(SchemaCompatibility.class.getResource(AsyncApi30.SCHEMA_RESOURCE),
                "Missing bundled AsyncAPI schema: " + AsyncApi30.SCHEMA_RESOURCE);
        var connection = resource.openConnection();
        connection.setUseCaches(false);
        return connection.getInputStream();
    }
}
