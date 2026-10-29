package io.github.sjacek.asyncwright.parser;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Explicit authority to read declared local Avro files. Construction reads no files.
 * An empty set allows inline Avro only. Paths are project-relative and normalized;
 * the parser also checks real paths before reading any referenced file.
 * The allowlist names files, not Avro symbols: a declared file does not contribute types
 * to another schema. Constructing this record does not require the root/files to exist.
 *
 * @param projectRoot directory containing the document and allowed schemas
 * @param schemaFiles complete allowlist of project-relative file names, copied immutably
 */
public record ParseContext(Path projectRoot, Set<String> schemaFiles) {
    /**
     * Normalizes the project root and snapshots portable file names.
     * @param projectRoot project directory, made absolute without filesystem access
     * @param schemaFiles allowed file paths; use forward slashes for portable configuration
     * @throws IllegalArgumentException if a file name is absolute or traverses outside the project
     */
    public ParseContext {
        projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        schemaFiles = Objects.requireNonNull(schemaFiles, "schemaFiles").stream()
                .map(path -> DirectoryOutput.relativePath(path).toString().replace('\\', '/'))
                .collect(Collectors.toUnmodifiableSet());
        if (schemaFiles.contains("")) throw new IllegalArgumentException("Expected schema file names");
    }
}
