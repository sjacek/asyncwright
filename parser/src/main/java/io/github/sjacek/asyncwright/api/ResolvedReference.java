package io.github.sjacek.asyncwright.api;

import com.fasterxml.jackson.databind.JsonNode;

/** The target's document pointer and a defensive copy of its source value. */
public record ResolvedReference(String pointer, JsonNode node) {
    public ResolvedReference { node = node.deepCopy(); }
    @Override public JsonNode node() { return node.deepCopy(); }
}
