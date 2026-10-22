package io.github.sjacek.asyncwright.parser;

import io.github.sjacek.asyncwright.api.GeneratedFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Validates helper writes; arbitrary extension code is trusted, not filesystem-sandboxed. */
final class DirectoryOutput implements GeneratedFiles {
    private final Path directory;
    private final Set<String> writtenKeys = new HashSet<>();
    private final List<String> writtenFiles = new ArrayList<>();

    DirectoryOutput(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }

    static Path relativePath(String value) {
        String portable = value.replace('\\', '/');
        if (portable.startsWith("/") || portable.matches("^[A-Za-z]:.*"))
            throw new IllegalArgumentException("Expected a relative path: " + value);
        Path path = Path.of(portable);
        if (path.isAbsolute()) throw new IllegalArgumentException("Expected a relative path: " + value);
        for (Path part : path) if (part.toString().equals(".."))
            throw new IllegalArgumentException("Parent traversal is not allowed: " + value);
        Path normalized = path.normalize();
        if (normalized.toString().isBlank()) throw new IllegalArgumentException("Expected a file path");
        return normalized;
    }

    @Override
    public void write(String relativePath, byte[] contents) throws IOException {
        Path relative = relativePath(relativePath);
        String name = relative.toString().replace('\\', '/');
        String key = name.toLowerCase(Locale.ROOT);
        if (writtenKeys.contains(key)) throw new IOException("Generated file already written: " + name);
        Files.createDirectories(directory);
        Path root = directory.toRealPath();
        Path parent = root;
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            parent = parent.resolve(relative.getName(i));
            if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(parent);
            if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                    || !parent.toRealPath().startsWith(root))
                throw new IOException("Expected an output directory without symbolic links: " + name);
        }
        Path file = root.resolve(relative);
        if (Files.isSymbolicLink(file)) throw new IOException("Symbolic output links are not allowed: " + name);
        Files.write(file, contents, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        writtenKeys.add(key);
        writtenFiles.add(name);
    }

    List<String> writtenFiles() { return List.copyOf(writtenFiles); }
}
