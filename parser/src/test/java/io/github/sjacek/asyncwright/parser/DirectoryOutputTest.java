package io.github.sjacek.asyncwright.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DirectoryOutputTest {
    @TempDir Path directory;

    @Test
    void aFailedWriteDoesNotReserveThePathOrReportAFileThatWasNotWritten() throws Exception {
        var output = new DirectoryOutput(directory);
        Path blocker = directory.resolve("nested");
        Files.writeString(blocker, "ordinary file");
        assertThrows(IOException.class, () -> output.writeUtf8("nested/result.txt", "first"));
        assertTrue(output.writtenFiles().isEmpty());
        assertEquals("ordinary file", Files.readString(blocker));

        Files.delete(blocker);
        output.writeUtf8("nested/result.txt", "second");
        assertEquals(List.of("nested/result.txt"), output.writtenFiles());
        assertEquals("second", Files.readString(directory.resolve("nested/result.txt")));
    }

    @Test
    void existingFilesAreReplacedButCanonicalAliasesCannotOverwriteWithinTheSameExecution() throws Exception {
        Files.writeString(directory.resolve("result.txt"), "obsolete longer contents");
        var output = new DirectoryOutput(directory);
        output.writeUtf8("./result.txt", "new");
        assertEquals("new", Files.readString(directory.resolve("result.txt")));
        assertEquals(List.of("result.txt"), output.writtenFiles());
        assertThrows(IOException.class, () -> output.writeUtf8("result.txt", "duplicate"));
        assertThrows(IOException.class, () -> output.writeUtf8("RESULT.txt", "case alias"));
        assertEquals("new", Files.readString(directory.resolve("result.txt")));
        assertEquals(List.of("result.txt"), output.writtenFiles());
    }
}
