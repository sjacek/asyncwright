package io.github.sjacek.asyncwright.api;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Snapshot of one validated Avro file; reading this value never accesses the filesystem.
 * Both the constructor argument and every returned tree are isolated from the stored snapshot.
 * The name is diagnostic provenance, not authority to reopen the file after parsing.
 * @param file normalized project-relative file name
 * @param node complete JSON schema, copied on construction and access
 */
public record ResolvedSchemaFile(String file, JsonNode node) {
    /**
     * Snapshots a validated file target.
     * @param file project-relative name
     * @param node schema tree
     */
    public ResolvedSchemaFile { node = node.deepCopy(); }

    /**
     * Returns a defensive schema copy.
     * @return complete Avro tree
     */
    @Override
    public JsonNode node() { return node.deepCopy(); }
}
