package io.github.sjacek.asyncwright.parser;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import io.github.sjacek.asyncwright.api.SourceLocation;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

final class SourceDocument {
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    final JsonNode root;
    final String file;
    private final Map<String, SourceLocation> positions;

    private SourceDocument(String file, JsonNode root, Map<String, SourceLocation> positions) {
        this.file = file;
        this.root = root;
        this.positions = Map.copyOf(positions);
    }

    static SourceDocument read(String file, String input) throws IOException {
        Map<String, SourceLocation> positions = new LinkedHashMap<>();
        try (JsonParser parser = YAML.createParser(input)) {
            if (parser.nextToken() == null) throw new IOException("The document is empty");
            JsonNode root = readNode(parser, file, "", positions);
            if (parser.nextToken() != null) throw new IOException("Expected a single YAML or JSON document");
            return new SourceDocument(file, root, positions);
        }
    }

    private static JsonNode readNode(JsonParser parser, String file, String pointer,
                                     Map<String, SourceLocation> positions) throws IOException {
        var location = parser.currentTokenLocation();
        if (parser instanceof YAMLParser yaml && yaml.isCurrentAlias())
            throw new JsonParseException(parser, "YAML aliases are not supported by this parser version");
        positions.put(pointer, new SourceLocation(file, Math.max(1, location.getLineNr()),
                Math.max(1, location.getColumnNr()), pointer));
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        if (parser.currentToken() == JsonToken.START_OBJECT) {
            ObjectNode object = nodes.objectNode();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) throw new IOException("Expected an object field");
                String name = parser.currentName();
                var keyLocation = parser.currentTokenLocation();
                String child = pointer + "/" + escape(name);
                if (parser.nextToken() == null) throw new IOException("Expected a field value");
                object.set(name, readNode(parser, file, child, positions));
                positions.put(child, new SourceLocation(file, Math.max(1, keyLocation.getLineNr()),
                        Math.max(1, keyLocation.getColumnNr()), child));
            }
            return object;
        }
        if (parser.currentToken() == JsonToken.START_ARRAY) {
            ArrayNode array = nodes.arrayNode();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (parser.currentToken() == null) throw new IOException("Unclosed array");
                array.add(readNode(parser, file, pointer + "/" + array.size(), positions));
            }
            return array;
        }
        return switch (parser.currentToken()) {
            case VALUE_STRING -> nodes.textNode(parser.getText());
            case VALUE_NUMBER_INT -> nodes.numberNode(parser.getBigIntegerValue());
            case VALUE_NUMBER_FLOAT -> nodes.numberNode(parser.getDecimalValue());
            case VALUE_TRUE -> nodes.booleanNode(true);
            case VALUE_FALSE -> nodes.booleanNode(false);
            case VALUE_NULL -> nodes.nullNode();
            default -> throw new IOException("Unexpected token " + parser.currentToken());
        };
    }

    SourceLocation location(String pointer) {
        String current = pointer;
        while (!positions.containsKey(current) && !current.isEmpty()) {
            current = current.substring(0, current.lastIndexOf('/'));
        }
        SourceLocation location = positions.getOrDefault(current, new SourceLocation(file, 1, 1, ""));
        return new SourceLocation(file, location.line(), location.column(), pointer);
    }

    static String escape(String token) { return token.replace("~", "~0").replace("/", "~1"); }
}
