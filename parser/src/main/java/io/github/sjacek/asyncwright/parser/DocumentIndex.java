package io.github.sjacek.asyncwright.parser;

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
        if (node.has("$ref")) {
            references.add(new Use(pointer, kind));
            if (node.size() > 1) diagnostics.add(Diagnostic.at("reference.siblings", Severity.WARNING,
                    "Fields beside $ref are ignored", source.location(pointer), List.of()));
            return;
        }
        if (kind == Kind.SCHEMA) {
            if (node.has("schemaFormat")) {
                if (Set.of("application/vnd.aai.asyncapi;version=3.0.0",
                        "application/vnd.aai.asyncapi+json;version=3.0.0",
                        "application/vnd.aai.asyncapi+yaml;version=3.0.0").contains(node.path("schemaFormat").asText()))
                    child(pointer, "schema", node.path("schema"), Kind.SCHEMA);
                else unsupported(pointer + "/schemaFormat", "Non-default schema formats are not supported in 0.1");
                return;
            }
            if (node.has("$schema") && !Set.of("http://json-schema.org/draft-07/schema",
                    "http://json-schema.org/draft-07/schema#", "https://json-schema.org/draft-07/schema",
                    "https://json-schema.org/draft-07/schema#").contains(node.path("$schema").asText()))
                diagnostics.add(Diagnostic.at("unsupported.schema-dialect", Severity.ERROR,
                        "Only the default AsyncAPI / JSON Schema draft-07 dialect is supported in 0.1",
                        source.location(pointer + "/$schema"), List.of()));
            if (node.has("$id"))
                diagnostics.add(Diagnostic.at("unsupported.schema-id", Severity.ERROR,
                        "Schema resource identifiers ($id) are not supported in 0.1; use document-local JSON Pointers",
                        source.location(pointer + "/$id"), List.of()));
            for (String key : List.of("properties", "patternProperties", "definitions", "$defs"))
                mapChildren(node, pointer, key, Kind.SCHEMA);
            for (String key : List.of("allOf", "oneOf", "anyOf"))
                listChildren(node, pointer, key, Kind.SCHEMA);
            for (String key : List.of("items", "additionalItems", "additionalProperties", "contains",
                    "propertyNames", "not", "if", "then", "else")) {
                JsonNode child = node.get(key);
                if (child != null && child.isArray()) listChildren(node, pointer, key, Kind.SCHEMA);
                else if (child != null && (child.isObject() || child.isBoolean()))
                    child(pointer, key, child, Kind.SCHEMA);
            }
            JsonNode dependencies = node.path("dependencies");
            for (var field : dependencies.properties()) if (field.getValue().isObject())
                child(pointer, "dependencies/" + SourceDocument.escape(field.getKey()), field.getValue(), Kind.SCHEMA);
            return;
        }
        for (var field : node.properties()) {
            String key = field.getKey();
            JsonNode value = field.getValue();
            if (key.startsWith("x-") || Set.of("examples", "example", "default", "const", "enum").contains(key)) continue;
            if (kind == Kind.COMPONENTS && Set.of("operationTraits", "messageTraits").contains(key) && !value.isEmpty()) {
                unsupported(pointer + "/" + key, "Traits are not supported in 0.1");
                continue;
            }
            if (key.equals("traits") && value.isArray() && !value.isEmpty()) {
                unsupported(pointer + "/traits", "Traits are not supported in 0.1");
                continue;
            }
            if (key.equals("schemaFormat")) {
                unsupported(pointer + "/schemaFormat", "Explicit schemaFormat is not supported in 0.1; use the default AsyncAPI schema");
                continue;
            }
            Kind mapKind = mapKind(kind, key);
            if (mapKind != null) { mapChildren(node, pointer, key, mapKind); continue; }
            if (key.equals("payload") || key.equals("headers")) {
                child(pointer, key, value, Kind.SCHEMA);
                continue;
            }
            Kind childKind = switch (key) {
                case "components" -> Kind.COMPONENTS;
                case "channel" -> Kind.CHANNEL;
                case "correlationId" -> Kind.CORRELATION;
                case "reply" -> Kind.REPLY;
                case "tags" -> Kind.TAG;
                case "bindings" -> Kind.BINDING;
                default -> Kind.OTHER;
            };
            if (key.equals("messages") && (kind == Kind.OPERATION || kind == Kind.REPLY)) childKind = Kind.MESSAGE;
            if (key.equals("servers") && kind == Kind.CHANNEL) childKind = Kind.SERVER;
            if (value.isArray()) listChildren(node, pointer, key, childKind);
            else if (value.isObject()) child(pointer, SourceDocument.escape(key), value, childKind);
        }
    }

    private static Kind mapKind(Kind parent, String field) {
        if (parent == Kind.ROOT || parent == Kind.COMPONENTS) return switch (field) {
            case "schemas" -> parent == Kind.COMPONENTS ? Kind.SCHEMA : null;
            case "channels" -> Kind.CHANNEL;
            case "operations" -> Kind.OPERATION;
            case "messages" -> Kind.MESSAGE;
            case "servers" -> Kind.SERVER;
            case "parameters" -> Kind.PARAMETER;
            case "securitySchemes" -> Kind.SECURITY;
            case "correlationIds" -> Kind.CORRELATION;
            case "tags" -> Kind.TAG;
            case "channelBindings", "operationBindings", "messageBindings", "serverBindings" -> Kind.BINDING;
            default -> null;
        };
        if (parent == Kind.CHANNEL && field.equals("messages")) return Kind.MESSAGE;
        if (parent == Kind.CHANNEL && field.equals("parameters")) return Kind.PARAMETER;
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
        diagnostics.add(Diagnostic.at("unsupported.construct", Severity.ERROR, message, source.location(pointer), List.of()));
    }
}
