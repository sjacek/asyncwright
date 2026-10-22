package io.github.sjacek.asyncwright.api;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The target's document pointer and a defensive copy of its source value.
 *
 * @param pointer decoded JSON Pointer of the concrete target
 * @param node source value copied on construction and every access
 */
public record ResolvedReference(String pointer, JsonNode node) {
    /**
     * Snapshots the target source value.
     * @param pointer decoded JSON Pointer of the concrete target
     * @param node source value to copy
     */
    public ResolvedReference { node = node.deepCopy(); }
    /**
     * Returns an independent copy of the target.
     * @return source tree that callers may mutate safely
     */
    @Override public JsonNode node() { return node.deepCopy(); }
}
