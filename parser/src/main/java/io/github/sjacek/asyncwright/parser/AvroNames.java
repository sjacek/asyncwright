package io.github.sjacek.asyncwright.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.avro.SchemaParseException;

import java.util.HashSet;
import java.util.Set;

/**
 * Checks definition-before-use in one standalone Avro 1.9 schema.
 * A new instance is required for each wrapper/file: declared input files do not form a shared
 * symbol table. This pass visits schema positions only; aliases, defaults and custom metadata
 * never define types. The Apache parser remains responsible for grammar and duplicate names.
 * Checking unresolved names here also avoids depending on the newer parser's deferred resolution.
 */
final class AvroNames {
    private static final Set<String> PRIMITIVES = Set.of("null", "boolean", "int", "long", "float", "double", "bytes", "string");
    private final Set<String> names = new HashSet<>();

    /** Starts in the empty namespace; a record's full name supplies its children's namespace. */
    void validate(JsonNode node) { visit(node, ""); }

    private void visit(JsonNode node, String namespace) {
        if (node.isTextual()) { reference(node.asText(), namespace); return; }
        if (node.isArray()) { node.forEach(child -> visit(child, namespace)); return; }
        if (!node.isObject()) return; // The Avro grammar supplies the type diagnostic.
        JsonNode type = node.path("type");
        if (type.isContainerNode()) { visit(type, namespace); return; }
        if (!type.isTextual()) return;
        switch (type.asText()) {
            case "record", "error", "enum", "fixed" -> {
                String name = node.path("name").asText();
                if (name.isEmpty()) return;
                String space = node.path("namespace").asText(namespace);
                // A dotted name overrides the namespace attribute, including for child references.
                String full = name.contains(".") ? name : qualified(name, space);
                names.add(full); // A record can refer to itself inside its fields.
                int separator = full.lastIndexOf('.');
                String scope = separator < 0 ? "" : full.substring(0, separator);
                if (type.asText().equals("record") || type.asText().equals("error"))
                    node.path("fields").forEach(field -> visit(field.path("type"), scope));
            }
            case "array" -> visit(node.path("items"), namespace);
            case "map" -> visit(node.path("values"), namespace);
            default -> reference(type.asText(), namespace);
        }
    }

    private void reference(String name, String namespace) {
        if (!PRIMITIVES.contains(name) && !names.contains(name.contains(".") ? name : qualified(name, namespace)))
            throw new SchemaParseException("Avro 1.9 type name must be defined before use: " + name);
    }

    private static String qualified(String name, String namespace) { return namespace.isEmpty() ? name : namespace + "." + name; }
}
