package io.github.sjacek.asyncwright.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sjacek.asyncwright.api.Avro19;
import io.github.sjacek.asyncwright.api.ResolvedSchemaFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Public value-object boundaries, independent of a successful parser invocation. */
class AvroApiTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "application/vnd.apache.avro", "application/vnd.apache.avro;version=1.10.0",
            "application/vnd.apache.avro;version=1.9.0 ", "application/vnd.apache.avro; version=1.9.0",
            "APPLICATION/VND.APACHE.AVRO;version=1.9.0"})
    void doesNotInferOrNormalizeUnsupportedMediaTypes(String value) {
        assertFalse(Avro19.supports(value));
    }

    @Test
    void contextNormalizesPortableNamesWithoutRequiringExistingFiles() {
        Path absent = directory.resolve("not-created");
        var context = new ParseContext(absent, Set.of("schemas\\event.avsc", "schemas/./event.avsc"));
        assertFalse(Files.exists(absent), "Declaring authority must not create a project directory");
        assertEquals(absent.toAbsolutePath().normalize(), context.projectRoot());
        assertEquals(Set.of("schemas/event.avsc"), context.schemaFiles());
        assertThrows(UnsupportedOperationException.class, () -> context.schemaFiles().clear());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "../event.avsc", "schemas/../event.avsc", "/tmp/event.avsc",
            "C:/schemas/event.avsc", "\\\\server\\share\\event.avsc"})
    void contextRejectsNonPortableAuthorityNamesBeforeAnyRead(String value) {
        assertThrows(IllegalArgumentException.class, () -> new ParseContext(directory, Set.of(value)));
    }

    @Test
    void fileSnapshotOwnsItsConstructorArgumentAsWellAsItsAccessResults() {
        var input = new ObjectMapper().createObjectNode().put("type", "string");
        var snapshot = new ResolvedSchemaFile("schemas/event.avsc", input);
        input.removeAll();
        assertEquals("string", snapshot.node().path("type").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot.node()).removeAll();
        assertEquals("string", snapshot.node().path("type").asText());
    }
}
