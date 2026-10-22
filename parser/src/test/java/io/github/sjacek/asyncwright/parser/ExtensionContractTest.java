package io.github.sjacek.asyncwright.parser;

import io.github.sjacek.asyncwright.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionContractTest {
    @TempDir Path output;
    private final ExtensionExecutor executor = new ExtensionExecutor();

    private static ParseResult valid() {
        return new AsyncApiParser().parse("contracts/events.yaml", """
                asyncapi: 3.0.0
                info: {title: Test, version: 1.0.0}
                """);
    }

    @ParameterizedTest
    @ValueSource(strings = {"blank-id", "null-id", "null-severity", "null-message", "backslash-file",
            "parent-file", "zero-line", "zero-column", "invalid-pointer", "related-file", "related-line",
            "related-pointer", "null-list", "null-element"})
    void invalidExtensionFindingsFailForBothCallbacksWithoutLeakingTheContextLoader(String invalid) {
        var spec = new ExtensionSpec(InvalidFindings.class.getName(), Map.of("invalid", invalid));
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        for (boolean generate : List.of(false, true)) {
            var failure = assertThrows(ExtensionExecutionException.class, () -> {
                if (generate) executor.generate(valid(), List.of(), List.of(spec), output);
                else executor.validate(valid(), List.of(spec));
            });
            assertEquals(InvalidFindings.class.getName(), failure.extensionClass());
            assertEquals(generate ? ExtensionExecutionException.Kind.GENERATOR : ExtensionExecutionException.Kind.RULE,
                    failure.kind());
            assertNotNull(failure.getCause());
            assertSame(previous, Thread.currentThread().getContextClassLoader());
        }
    }

    @Test
    void linkageFailuresRetainTheirIdentityAndRestoreTheContextLoader() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        var failure = assertThrows(ExtensionExecutionException.class, () -> executor.validate(valid(),
                List.of(new ExtensionSpec(LinkageFailure.class.getName(), Map.of()))));
        assertInstanceOf(NoClassDefFoundError.class, failure.getCause());
        assertEquals("missing/Dependency", failure.getCause().getMessage());
        assertSame(previous, Thread.currentThread().getContextClassLoader());
    }

    @Test
    void aManuallyConstructedSuccessfulResultWithoutAModelIsRejectedBeforeLoadingExtensions() {
        var incomplete = new ParseResult(Optional.empty(), List.of());
        var failure = assertThrows(IllegalArgumentException.class, () -> executor.validate(incomplete,
                List.of(new ExtensionSpec("missing.Rule", Map.of()))));
        assertTrue(failure.getMessage().contains("must contain a document"));
    }

    @Test
    void missingPropertyLocationsRetainTheRequestedPointerAndUseTheNearestExistingContainer() {
        var document = valid().document().orElseThrow();
        var existing = document.location("/info");
        var missing = document.location("/info/missing/nested");
        assertEquals(existing.line(), missing.line());
        assertEquals(existing.column(), missing.column());
        assertEquals("/info/missing/nested", missing.pointer());
        assertEquals("contracts/events.yaml", missing.file());
    }

    public static class InvalidFindings implements AsyncApiRule, AsyncApiGenerator {
        @Override public List<Diagnostic> validate(ExtensionContext context) { return findings(context); }
        @Override public List<Diagnostic> generate(GeneratorContext context) { return findings(context); }

        private static List<Diagnostic> findings(ExtensionContext context) {
            String invalid = context.requireParameter("invalid");
            if (invalid.equals("null-list")) return null;
            if (invalid.equals("null-element")) return Collections.singletonList(null);
            String file = switch (invalid) {
                case "backslash-file" -> "contracts\\events.yaml";
                case "parent-file" -> "../events.yaml";
                default -> "contracts/events.yaml";
            };
            List<SourceLocation> related = switch (invalid) {
                case "related-file" -> List.of(new SourceLocation("/outside.yaml", 1, 1, ""));
                case "related-line" -> List.of(new SourceLocation(file, 0, 1, ""));
                case "related-pointer" -> List.of(new SourceLocation(file, 1, 1, "not-a-pointer"));
                default -> List.of();
            };
            return List.of(new Diagnostic(invalid.equals("null-id") ? null : invalid.equals("blank-id") ? " " : "test.invalid",
                    invalid.equals("null-severity") ? null : Severity.WARNING,
                    invalid.equals("null-message") ? null : "Finding", file,
                    invalid.equals("zero-line") ? 0 : 1, invalid.equals("zero-column") ? 0 : 1,
                    invalid.equals("invalid-pointer") ? "not-a-pointer" : "", related));
        }
    }

    public static class LinkageFailure implements AsyncApiRule {
        @Override public List<Diagnostic> validate(ExtensionContext context) {
            throw new NoClassDefFoundError("missing/Dependency");
        }
    }
}
