package io.github.sjacek.asyncwright.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Output access supplied by the host, scoped to one directory; write each relative path once per execution. */
public interface GeneratedFiles {
    void write(String relativePath, byte[] contents) throws IOException;

    default void writeUtf8(String relativePath, String contents) throws IOException {
        write(relativePath, contents.getBytes(StandardCharsets.UTF_8));
    }
}
