package io.github.sjacek.asyncwright.parser;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.github.sjacek.asyncwright.api.AsyncApiFields.HEADERS;
import static io.github.sjacek.asyncwright.parser.DocumentIndex.Kind.*;

/**
 * Schema-valued fields in the pinned protocol bindings. The owner and protocol are part of
 * the address: a configuration property merely named key or headers is still instance data.
 * Scalar alternatives (for example MQTT expiry intervals) do not create schema graph nodes.
 * The entries are the union of schema positions in all bundled binding versions. Structural
 * validation first decides which fields and scalar alternatives a specific version permits.
 * The registry coverage test must be reviewed whenever the pinned schema is upgraded.
 */
final class BindingSchemaPositions {
    /**
     * Wire-level field address. Protocols use document keys (not Java class names or schema
     * resource directories); WebSockets is {@code ws} here and {@code websockets} upstream.
     */
    record Position(String protocol, DocumentIndex.Kind owner, String field) { }

    /** Stable order also gives the parameterized protocol regressions useful, repeatable names. */
    static final List<Position> ALL = List.of(
            new Position("kafka", MESSAGE_BINDINGS, "key"),
            new Position("kafka", OPERATION_BINDINGS, "groupId"),
            new Position("kafka", OPERATION_BINDINGS, "clientId"),
            new Position("http", MESSAGE_BINDINGS, HEADERS),
            new Position("http", OPERATION_BINDINGS, "query"),
            new Position("ws", CHANNEL_BINDINGS, "query"),
            new Position("ws", CHANNEL_BINDINGS, HEADERS),
            new Position("jms", MESSAGE_BINDINGS, HEADERS),
            new Position("anypointmq", MESSAGE_BINDINGS, HEADERS),
            new Position("mqtt", SERVER_BINDINGS, "sessionExpiryInterval"),
            new Position("mqtt", SERVER_BINDINGS, "maximumPacketSize"),
            new Position("mqtt", OPERATION_BINDINGS, "messageExpiryInterval"),
            new Position("mqtt", MESSAGE_BINDINGS, "correlationData"),
            new Position("mqtt", MESSAGE_BINDINGS, "responseTopic"));

    private static final Set<Position> LOOKUP = Set.copyOf(ALL);
    private static final Set<DocumentIndex.Kind> OWNERS = ALL.stream()
            .map(Position::owner).collect(Collectors.toUnmodifiableSet());

    private BindingSchemaPositions() { }

    /** Whole binding maps are referenceable owners; their protocol configuration stays opaque. */
    static boolean isBindingMap(DocumentIndex.Kind kind) { return OWNERS.contains(kind); }

    /** Tests an exact owner/protocol/field address; nested instance data never uses this lookup. */
    static boolean contains(String protocol, DocumentIndex.Kind owner, String field) {
        return LOOKUP.contains(new Position(protocol, owner, field));
    }
}
