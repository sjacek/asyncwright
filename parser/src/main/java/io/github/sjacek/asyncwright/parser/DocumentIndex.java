package io.github.sjacek.asyncwright.parser;

import io.github.sjacek.asyncwright.api.AsyncApi30;
import io.github.sjacek.asyncwright.api.Avro19;
import io.github.sjacek.asyncwright.api.BuiltinRuleId;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.sjacek.asyncwright.api.Diagnostic;
import io.github.sjacek.asyncwright.api.Severity;

import java.util.*;

/**
 * Indexes document positions whose references carry a model kind, plus graph containment edges.
 * Binding maps carry their owner's kind, while protocol values are not reusable component names.
 * Instance values and extension data are excluded so a literal {@code $ref} cannot become an edge.
 */
final class DocumentIndex {
    private static final java.util.regex.Pattern COMPONENT_NAME =
            java.util.regex.Pattern.compile("[a-zA-Z0-9.\\-_]+");
    /** Distinct binding-map kinds prevent structurally similar maps from crossing owner boundaries. */
    enum Kind {
        SERVER_BINDINGS, CHANNEL_BINDINGS, OPERATION_BINDINGS, MESSAGE_BINDINGS,
        ROOT, COMPONENTS, CHANNEL(CHANNEL_BINDINGS), OPERATION(OPERATION_BINDINGS),
        MESSAGE(MESSAGE_BINDINGS), SERVER(SERVER_BINDINGS), SCHEMA, PARAMETER,
        SECURITY, TAG, CORRELATION, REPLY, AVRO_SCHEMA, OTHER;

        /** Associated map kind for an owner, or null for nodes without an owned binding map. */
        private final Kind bindings;

        Kind() { this.bindings = null; }
        Kind(Kind bindings) { this.bindings = bindings; }

        /** Returns the owned binding-map kind, or OTHER when this kind does not own bindings. */
        Kind bindingsKind() { return bindings == null ? OTHER : bindings; }
    }
    /** Source position of a reference and the kind required at its immediate target. */
    record Use(String pointer, Kind kind) { }
    final Map<String, Kind> kinds = new LinkedHashMap<>();
    final Map<String, List<String>> children = new LinkedHashMap<>();
    final List<Use> references = new ArrayList<>();
    final List<Diagnostic> diagnostics = new ArrayList<>();
    final List<String> avroSchemas = new ArrayList<>();
    private final SourceDocument source;

    DocumentIndex(SourceDocument source) {
        this.source = source;
        visit(source.root, "", Kind.ROOT);
    }

    private void visit(JsonNode node, String pointer, Kind kind) {
        kinds.put(pointer, kind);
        children.put(pointer, new ArrayList<>());
        // Avro names are not JSON Pointer edges. Only a wrapper's schema file reference is
        // referenceable here; $ref in defaults or arbitrary Avro metadata remains plain data.
        if (kind == Kind.AVRO_SCHEMA) {
            avroSchemas.add(pointer);
            if (node.isObject() && node.has(REF) && !node.has(TYPE)) reference(node, pointer, kind);
            return;
        }
        if (!node.isObject()) return;
        if (node.has(REF)) {
            reference(node, pointer, kind);
            return;
        }
        if (BindingSchemaPositions.isBindingMap(kind)) {
            visitBindingMap(node, pointer, kind);
            return;
        }
        if (kind == Kind.COMPONENTS) {
            // Inspect map keys, not the definitions' contents. Traits still need valid names
            // even though their merge semantics are unsupported; x-* component data is opaque.
            for (var collection : node.properties()) {
                if (mapKind(kind, collection.getKey()) == null
                        && !Set.of(OPERATION_TRAITS, MESSAGE_TRAITS).contains(collection.getKey())) continue;
                for (var field : collection.getValue().properties())
                    if (!COMPONENT_NAME.matcher(field.getKey()).matches())
                        diagnostics.add(Diagnostic.at(BuiltinRuleId.COMPONENT_NAME, Severity.ERROR,
                                "Component names must match [a-zA-Z0-9.\\-_]+",
                                source.location(pointer + "/" + collection.getKey() + "/"
                                        + SourceDocument.escape(field.getKey())), List.of()));
            }
        }
        if (kind == Kind.SCHEMA) {
            if (node.has(SCHEMA_FORMAT) || node.has(SCHEMA)) {
                if (Avro19.supports(node.path(SCHEMA_FORMAT).asText()) && multiFormatPosition(pointer))
                    child(pointer, SCHEMA, node.path(SCHEMA), Kind.AVRO_SCHEMA);
                else if (!node.has(SCHEMA_FORMAT) || AsyncApi30.supportsSchemaFormat(node.path(SCHEMA_FORMAT).asText()))
                    child(pointer, SCHEMA, node.path(SCHEMA), Kind.SCHEMA);
                else unsupported(pointer + "/" + SCHEMA_FORMAT, "Non-default schema formats are not supported in 0.1");
                return;
            }
            if (node.has(SCHEMA_DIALECT) && !AsyncApi30.supportsSchemaDialect(node.path(SCHEMA_DIALECT).asText()))
                diagnostics.add(Diagnostic.at(BuiltinRuleId.UNSUPPORTED_SCHEMA_DIALECT, Severity.ERROR,
                        "Only the default AsyncAPI / JSON Schema draft-07 dialect is supported in 0.1",
                        source.location(pointer + "/" + SCHEMA_DIALECT), List.of()));
            if (node.has(SCHEMA_ID))
                diagnostics.add(Diagnostic.at(BuiltinRuleId.UNSUPPORTED_SCHEMA_ID, Severity.ERROR,
                        "Schema resource identifiers ($id) are not supported in 0.1; use document-local JSON Pointers",
                        source.location(pointer + "/" + SCHEMA_ID), List.of()));
            for (String key : List.of(PROPERTIES, PATTERN_PROPERTIES, DEFINITIONS, DEFS))
                mapChildren(node, pointer, key, Kind.SCHEMA);
            for (String key : List.of(ALL_OF, ONE_OF, ANY_OF))
                listChildren(node, pointer, key, Kind.SCHEMA);
            for (String key : List.of(ITEMS, ADDITIONAL_ITEMS, ADDITIONAL_PROPERTIES, CONTAINS,
                    PROPERTY_NAMES, NOT, IF, THEN, ELSE)) {
                JsonNode child = node.get(key);
                if (child != null && child.isArray()) listChildren(node, pointer, key, Kind.SCHEMA);
                else if (child != null && (child.isObject() || child.isBoolean()))
                    child(pointer, key, child, Kind.SCHEMA);
            }
            JsonNode dependencies = node.path(DEPENDENCIES);
            for (var field : dependencies.properties()) if (field.getValue().isObject() || field.getValue().isBoolean())
                child(pointer, DEPENDENCIES + "/" + SourceDocument.escape(field.getKey()), field.getValue(), Kind.SCHEMA);
            return;
        }
        for (var field : node.properties()) {
            String key = field.getKey();
            JsonNode value = field.getValue();
            // Instance data may contain a literal $ref; only schema/specification positions carry references.
            if (key.startsWith(EXTENSION_PREFIX) || Set.of(EXAMPLES, EXAMPLE, DEFAULT, CONST, ENUM).contains(key)) continue;
            if (kind == Kind.COMPONENTS && Set.of(OPERATION_TRAITS, MESSAGE_TRAITS).contains(key) && !value.isEmpty()) {
                unsupported(pointer + "/" + key, "Traits are not supported in 0.1");
                continue;
            }
            if (key.equals(TRAITS) && value.isArray() && !value.isEmpty()) {
                unsupported(pointer + "/" + TRAITS, "Traits are not supported in 0.1");
                continue;
            }
            if (key.equals(SCHEMA_FORMAT)) {
                unsupported(pointer + "/" + SCHEMA_FORMAT, "Explicit schemaFormat is not supported in 0.1; use the default AsyncAPI schema");
                continue;
            }
            Kind mapKind = mapKind(kind, key);
            if (mapKind != null) { mapChildren(node, pointer, key, mapKind); continue; }
            if (key.equals(PAYLOAD) || key.equals(HEADERS)) {
                child(pointer, key, value, Kind.SCHEMA);
                continue;
            }
            Kind childKind = switch (key) {
                case COMPONENTS -> Kind.COMPONENTS;
                case CHANNEL -> Kind.CHANNEL;
                case CORRELATION_ID -> Kind.CORRELATION;
                case REPLY -> Kind.REPLY;
                case TAGS -> Kind.TAG;
                // Whole-map references must retain the owning kind; a channel map is not a server map.
                case BINDINGS -> kind.bindingsKind();
                default -> Kind.OTHER;
            };
            if (key.equals(MESSAGES) && (kind == Kind.OPERATION || kind == Kind.REPLY)) childKind = Kind.MESSAGE;
            if (key.equals(SERVERS) && kind == Kind.CHANNEL) childKind = Kind.SERVER;
            if (value.isArray()) listChildren(node, pointer, key, childKind);
            else if (value.isObject()) child(pointer, SourceDocument.escape(key), value, childKind);
        }
    }

    private static Kind mapKind(Kind parent, String field) {
        if (parent == Kind.ROOT || parent == Kind.COMPONENTS) return switch (field) {
            case SCHEMAS -> parent == Kind.COMPONENTS ? Kind.SCHEMA : null;
            case CHANNELS -> Kind.CHANNEL;
            case OPERATIONS -> Kind.OPERATION;
            case MESSAGES -> Kind.MESSAGE;
            case SERVERS -> Kind.SERVER;
            case PARAMETERS -> Kind.PARAMETER;
            case SECURITY_SCHEMES -> Kind.SECURITY;
            case CORRELATION_IDS -> Kind.CORRELATION;
            case TAGS -> Kind.TAG;
            case SERVER_BINDINGS -> Kind.SERVER_BINDINGS;
            case CHANNEL_BINDINGS -> Kind.CHANNEL_BINDINGS;
            case OPERATION_BINDINGS -> Kind.OPERATION_BINDINGS;
            case MESSAGE_BINDINGS -> Kind.MESSAGE_BINDINGS;
            // Reply aliases need the same kind as inline operation replies, including their channels/messages.
            case REPLIES -> Kind.REPLY;
            default -> null;
        };
        if (parent == Kind.CHANNEL && field.equals(MESSAGES)) return Kind.MESSAGE;
        if (parent == Kind.CHANNEL && field.equals(PARAMETERS)) return Kind.PARAMETER;
        return null;
    }

    /** Avro wrappers belong to payloads/components; protocol schema fields use refs to components. */
    private boolean multiFormatPosition(String pointer) {
        String components = "/" + COMPONENTS + "/" + SCHEMAS + "/";
        if (pointer.startsWith(components) && pointer.indexOf('/', components.length()) < 0) return true;
        int slash = pointer.lastIndexOf('/');
        return slash >= 0 && pointer.substring(slash + 1).equals(PAYLOAD)
                && kinds.get(pointer.substring(0, slash)) == Kind.MESSAGE;
    }

    private void mapChildren(JsonNode node, String pointer, String key, Kind kind) {
        for (var field : node.path(key).properties()) {
            child(pointer, key + "/" + SourceDocument.escape(field.getKey()), field.getValue(), kind);
        }
    }

    /**
     * Adds only schema-valued fields to the graph. Whole-map references were already handled
     * by visit(), so aliases keep their owner's kind. Protocol containers are containment nodes,
     * not schema targets. Booleans are schemas, whereas MQTT numbers/strings are scalar alternatives.
     * Never recurse generically through topic configuration: its literal $ref values are data.
     */
    private void visitBindingMap(JsonNode node, String pointer, Kind owner) {
        for (var protocol : node.properties()) {
            if (protocol.getKey().startsWith(EXTENSION_PREFIX)) continue;
            String binding = pointer + "/" + SourceDocument.escape(protocol.getKey());
            kinds.put(binding, Kind.OTHER);
            children.put(binding, new ArrayList<>());
            children.get(pointer).add(binding);
            for (var field : protocol.getValue().properties()) {
                JsonNode value = field.getValue();
                if (BindingSchemaPositions.contains(protocol.getKey(), owner, field.getKey())
                        && (value.isObject() || value.isBoolean()))
                    child(binding, SourceDocument.escape(field.getKey()), value, Kind.SCHEMA);
            }
        }
    }

    private void listChildren(JsonNode node, String pointer, String key, Kind kind) {
        JsonNode array = node.path(key);
        if (array.isArray()) for (int i = 0; i < array.size(); i++) child(pointer, key + "/" + i, array.get(i), kind);
    }

    private void child(String parent, String suffix, JsonNode node, Kind kind) {
        String pointer = parent + "/" + suffix;
        children.get(parent).add(pointer);
        visit(node, pointer, kind);
    }

    private void unsupported(String pointer, String message) {
        diagnostics.add(Diagnostic.at(BuiltinRuleId.UNSUPPORTED_CONSTRUCT, Severity.ERROR, message, source.location(pointer), List.of()));
    }

    private void reference(JsonNode node, String pointer, Kind kind) {
        references.add(new Use(pointer, kind));
        if (node.size() > 1) diagnostics.add(Diagnostic.at(BuiltinRuleId.REFERENCE_SIBLINGS, Severity.WARNING,
                "Fields beside $ref are ignored", source.location(pointer), List.of()));
    }
}
