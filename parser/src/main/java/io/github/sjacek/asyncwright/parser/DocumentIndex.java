package io.github.sjacek.asyncwright.parser;

import io.github.sjacek.asyncwright.api.AsyncApi30;
import io.github.sjacek.asyncwright.api.BuiltinRuleId;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.sjacek.asyncwright.api.Diagnostic;
import io.github.sjacek.asyncwright.api.Severity;

import java.util.*;

/** Indexes schema-bearing positions, excluding example/default data and extension values. */
final class DocumentIndex {
    enum Kind { ROOT, COMPONENTS, CHANNEL, OPERATION, MESSAGE, SERVER, SCHEMA, PARAMETER,
        SECURITY, TAG, CORRELATION, REPLY, BINDING, OTHER }
    record Use(String pointer, Kind kind) { }
    final Map<String, Kind> kinds = new LinkedHashMap<>();
    final Map<String, List<String>> children = new LinkedHashMap<>();
    final List<Use> references = new ArrayList<>();
    final List<Diagnostic> diagnostics = new ArrayList<>();
    private final SourceDocument source;

    DocumentIndex(SourceDocument source) {
        this.source = source;
        visit(source.root, "", Kind.ROOT);
    }

    private void visit(JsonNode node, String pointer, Kind kind) {
        kinds.put(pointer, kind);
        children.put(pointer, new ArrayList<>());
        if (!node.isObject()) return;
        if (node.has(REF)) {
            references.add(new Use(pointer, kind));
            if (node.size() > 1) diagnostics.add(Diagnostic.at(BuiltinRuleId.REFERENCE_SIBLINGS, Severity.WARNING,
                    "Fields beside $ref are ignored", source.location(pointer), List.of()));
            return;
        }
        if (kind == Kind.SCHEMA) {
            if (node.has(SCHEMA_FORMAT) || node.has(SCHEMA)) {
                if (!node.has(SCHEMA_FORMAT) || AsyncApi30.supportsSchemaFormat(node.path(SCHEMA_FORMAT).asText()))
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
                case BINDINGS -> Kind.BINDING;
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
            case CHANNEL_BINDINGS, OPERATION_BINDINGS, MESSAGE_BINDINGS, SERVER_BINDINGS -> Kind.BINDING;
            default -> null;
        };
        if (parent == Kind.CHANNEL && field.equals(MESSAGES)) return Kind.MESSAGE;
        if (parent == Kind.CHANNEL && field.equals(PARAMETERS)) return Kind.PARAMETER;
        return null;
    }

    private void mapChildren(JsonNode node, String pointer, String key, Kind kind) {
        for (var field : node.path(key).properties())
            child(pointer, key + "/" + SourceDocument.escape(field.getKey()), field.getValue(), kind);
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
}
