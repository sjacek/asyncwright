package io.github.sjacek.asyncwright.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Output access supplied by the host, scoped to one directory; write each relative path once per execution. */
public interface GeneratedFiles {
    /**
     * Writes one file below the host output directory. Separators may be slashes or backslashes.
     * Absolute paths, parent traversal, symbolic links and repeated paths (ignoring case) are rejected.
     * An existing ordinary file may be replaced by the first write in a new execution.
     * This is an output discipline for trusted extensions, not a sandbox for arbitrary Java code.
     * @param relativePath non-empty relative file path
     * @param contents complete file bytes
     * @throws IllegalArgumentException if the path is absolute, empty or contains parent traversal
     * @throws IOException if a path contains a symbolic link, is already written, or cannot be written
     */
    void write(String relativePath, byte[] contents) throws IOException;

    /**
     * Writes text as UTF-8 without adding a byte-order mark or changing line endings.
     * @param relativePath non-empty relative file path
     * @param contents complete text
     * @throws IllegalArgumentException if the path is absolute, empty or contains parent traversal
     * @throws IOException if a path contains a symbolic link, is already written, or cannot be written
     */
    default void writeUtf8(String relativePath, String contents) throws IOException {
        write(relativePath, contents.getBytes(StandardCharsets.UTF_8));
    }
}
