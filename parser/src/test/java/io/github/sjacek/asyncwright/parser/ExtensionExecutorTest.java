package io.github.sjacek.asyncwright.parser;

import com.asyncapi.v3._0_0.model.AsyncAPI;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sjacek.asyncwright.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ExtensionExecutorTest {
    @TempDir Path directory;
    private final AsyncApiParser parser = new AsyncApiParser();
    private final ExtensionExecutor executor = new ExtensionExecutor();
    private static final String VALID = """
            asyncapi: 3.0.0
            info: {title: Original, version: 1.0.0}
            channels:
              events: {address: events}
            """;

    private static ExtensionSpec spec(Class<?> type) { return new ExtensionSpec(type.getName(), Map.of()); }
    private ParseResult valid() { return parser.parse("specs/events.yaml", VALID); }

    @Test
    void parserErrorsSkipLoadingRulesAndGeneratorsAndLeaveOutputsUntouched() throws Exception {
        var invalid = parser.parse("specs/events.yaml", "asyncapi: 3.0.0");
        Path output = directory.resolve("output");
        var result = executor.generate(invalid, List.of(new ExtensionSpec("missing.Rule", Map.of())),
                List.of(new ExtensionSpec("missing.Generator", Map.of())), output);
        assertEquals(invalid.diagnostics(), result.diagnostics());
        assertFalse(result.successful());
        assertTrue(result.generatedFiles().isEmpty());
        assertFalse(Files.exists(output));
    }

    @Test
    void allRulesRunBeforeErrorsPreventGeneratorLoading() throws Exception {
        var result = executor.generate(valid(), List.of(spec(ErrorRule.class), spec(WarningRule.class), spec(ErrorRule.class)),
                List.of(new ExtensionSpec("missing.Generator", Map.of())), directory.resolve("output"));
        assertFalse(result.successful());
        assertEquals(List.of("test.error", "test.warning"), result.diagnostics().stream().map(Diagnostic::ruleId).toList());
        assertFalse(Files.exists(directory.resolve("output")));
    }

    @Test
    void parserAndRuleWarningsAllowGenerationWithImmutableTextParameters() throws Exception {
        Map<String, String> parameters = new HashMap<>(Map.of("path", "nested\\file.txt", "value", "Zażółć"));
        var generator = new ExtensionSpec(TextGenerator.class.getName(), parameters);
        parameters.put("value", "changed");
        assertThrows(UnsupportedOperationException.class, () -> generator.parameters().put("other", "value"));
        var parsed = parser.parse("specs/events.yaml", VALID + """
                operations:
                  send:
                    action: send
                    channel: {$ref: '#/channels/events', ignored: true}
                """);
        var result = executor.generate(parsed, List.of(spec(WarningRule.class)), List.of(generator), directory);
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertEquals(2, result.diagnostics().size());
        assertEquals(List.of("nested/file.txt"), result.generatedFiles());
        assertEquals("Zażółć", Files.readString(directory.resolve("nested/file.txt")));
        assertThrows(UnsupportedOperationException.class, () -> result.generatedFiles().add("extra"));
    }

    @Test
    void mutationsStayLocalToOneInvocationAndCannotChangeTheOriginalDocument() throws Exception {
        var parsed = valid();
        var result = executor.generate(parsed, List.of(spec(MutatingRule.class), spec(OriginalRule.class)),
                List.of(spec(TitleGenerator.class)), directory);
        assertTrue(result.successful());
        assertEquals("Original", Files.readString(directory.resolve("title.txt")));
        assertEquals("Original", Objects.requireNonNull(parsed.document().orElseThrow().model().getInfo()).getTitle());
        assertTrue(parsed.document().orElseThrow().source().has("channels"));
    }

    @Test
    void extensionDiagnosticsAndOutputsAreStableAfterRelocation() throws Exception {
        Path first = Files.createDirectories(directory.resolve("first/specs"));
        Path second = Files.createDirectories(directory.resolve("second/specs"));
        Files.writeString(first.resolve("events.yaml"), VALID);
        Files.writeString(second.resolve("events.yaml"), VALID);
        var a = executor.generate(parser.parse(first.getParent(), Path.of("specs/events.yaml")), List.of(spec(WarningRule.class)),
                List.of(spec(TitleGenerator.class)), first.getParent().resolve("output"));
        var b = executor.generate(parser.parse(second.getParent(), Path.of("specs/events.yaml")), List.of(spec(WarningRule.class)),
                List.of(spec(TitleGenerator.class)), second.getParent().resolve("output"));
        assertEquals(a.diagnosticsJson(), b.diagnosticsJson());
        assertEquals(a.generatedFiles(), b.generatedFiles());
        assertArrayEquals(Files.readAllBytes(first.getParent().resolve("output/title.txt")),
                Files.readAllBytes(second.getParent().resolve("output/title.txt")));
        assertFalse(a.diagnosticsJson().contains(directory.toString()));
    }

    @Test
    void callbackFailuresKeepTheExtensionIdentityKindAndCause() {
        var failure = assertThrows(ExtensionExecutionException.class, () -> executor.validate(valid(), List.of(spec(FailingRule.class))));
        assertEquals(FailingRule.class.getName(), failure.extensionClass());
        assertEquals(ExtensionExecutionException.Kind.RULE, failure.kind());
        assertInstanceOf(IOException.class, failure.getCause());
        assertEquals("rule failed", failure.getCause().getMessage());
        var generation = assertThrows(ExtensionExecutionException.class, () -> executor.generate(valid(), List.of(),
                List.of(spec(FailingGenerator.class)), directory));
        assertEquals(ExtensionExecutionException.Kind.GENERATOR, generation.kind());
        assertInstanceOf(IOException.class, generation.getCause());
    }

    @Test
    void loadingFailuresAreDistinctFromContractDiagnostics() {
        var missing = assertThrows(ExtensionExecutionException.class,
                () -> executor.validate(valid(), List.of(new ExtensionSpec("missing.Rule", Map.of()))));
        assertInstanceOf(ClassNotFoundException.class, missing.getCause());
        var wrongType = assertThrows(ExtensionExecutionException.class,
                () -> executor.validate(valid(), List.of(spec(String.class))));
        assertInstanceOf(IllegalArgumentException.class, wrongType.getCause());
        var constructor = assertThrows(ExtensionExecutionException.class,
                () -> executor.validate(valid(), List.of(spec(NoPublicConstructorRule.class))));
        assertInstanceOf(NoSuchMethodException.class, constructor.getCause());
        var throwingConstructor = assertThrows(ExtensionExecutionException.class,
                () -> executor.validate(valid(), List.of(spec(FailingConstructorRule.class))));
        assertInstanceOf(IOException.class, throwingConstructor.getCause());
    }

    @Test
    void invalidCallbackResultsAndMissingParametersFailWithTheExtensionIdentity() {
        for (Class<?> type : List.of(NullRule.class, AbsoluteDiagnosticRule.class, RequiredParameterRule.class)) {
            var failure = assertThrows(ExtensionExecutionException.class, () -> executor.validate(valid(), List.of(spec(type))));
            assertEquals(type.getName(), failure.extensionClass());
        }
    }

    @Test
    void generatorErrorsStopLaterGeneratorsAndReportAlreadyWrittenPaths() throws Exception {
        var result = executor.generate(valid(), List.of(), List.of(spec(ErrorGenerator.class),
                new ExtensionSpec("missing.Generator", Map.of())), directory);
        assertFalse(result.successful());
        assertEquals(List.of("partial.txt"), result.generatedFiles());
        assertEquals("test.generator", result.diagnostics().get(0).ruleId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "..", "../outside.txt", "nested/../../outside.txt", "/outside.txt", "C:/outside.txt", "C:outside.txt", "\\\\host\\share\\outside.txt"})
    void outputHelperRejectsAbsoluteEmptyAndParentPaths(String path) {
        var failure = assertThrows(ExtensionExecutionException.class, () -> executor.generate(valid(), List.of(),
                List.of(new ExtensionSpec(TextGenerator.class.getName(), Map.of("path", path, "value", "text"))), directory.resolve("output")));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertFalse(Files.exists(directory.resolve("output")));
    }

    @Test
    void generatorsCannotSilentlyOverwriteEachOthersFilesIncludingCaseAliases() {
        var failure = assertThrows(ExtensionExecutionException.class, () -> executor.generate(valid(), List.of(), List.of(
                new ExtensionSpec(TextGenerator.class.getName(), Map.of("path", "file.txt", "value", "first")),
                new ExtensionSpec(TextGenerator.class.getName(), Map.of("path", "FILE.txt", "value", "second"))), directory));
        assertInstanceOf(IOException.class, failure.getCause());
        assertDoesNotThrow(() -> assertEquals("first", Files.readString(directory.resolve("file.txt"))));
    }

    @Test
    void helperRejectsSymbolicLinksInsideTheOutputDirectoryWhenSupported() throws Exception {
        Path outside = Files.createDirectories(directory.resolve("outside"));
        Path output = Files.createDirectories(directory.resolve("output"));
        try { Files.createSymbolicLink(output.resolve("linked"), outside); }
        catch (IOException | UnsupportedOperationException exception) { assumeTrue(false, "Symbolic links unavailable on this host"); }
        var failure = assertThrows(ExtensionExecutionException.class, () -> executor.generate(valid(), List.of(),
                List.of(new ExtensionSpec(TextGenerator.class.getName(), Map.of("path", "linked/file.txt", "value", "text"))), output));
        assertInstanceOf(IOException.class, failure.getCause());
        assertFalse(Files.exists(outside.resolve("file.txt")));
    }

    @Test
    void snapshotsPreserveEveryAcceptedPublicCorpusModel() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/conformance/manifest.json"))) {
            for (JsonNode entry : mapper.readTree(input).path("cases")) {
                String file = entry.path("file").asText();
                String text;
                try (var bytes = Objects.requireNonNull(getClass().getResourceAsStream("/conformance/" + file))) {
                    text = new String(bytes.readAllBytes(), StandardCharsets.UTF_8);
                }
                var parsed = parser.parse(file, text);
                if (!parsed.successful()) continue;
                var result = executor.validate(parsed, List.of(new ExtensionSpec(SnapshotRule.class.getName(),
                        Map.of("expected", mapper.writeValueAsString(parsed.document().orElseThrow().model())))));
                assertTrue(result.successful(), file);
            }
        }
    }

    @Test
    void loadsAnIndependentlyCompiledJarUsingOnlyPublicApiAndRestoresTheContextLoader() throws Exception {
        Path source = directory.resolve("ExternalRule.java");
        Files.writeString(source, """
                package external;
                import io.github.sjacek.asyncwright.api.*;
                import java.util.List;
                public class ExternalRule implements AsyncApiRule {
                    public List<Diagnostic> validate(ExtensionContext context) {
                        if (Thread.currentThread().getContextClassLoader() != getClass().getClassLoader())
                            throw new IllegalStateException("Wrong context classloader");
                        if (context.parameters().containsKey("fail")) throw new IllegalStateException("External failure");
                        return List.of(context.diagnostic("external.rule", Severity.WARNING,
                            context.requireParameter("message"), "/info/title"));
                    }
                }
                """);
        Path classes = Files.createDirectories(directory.resolve("classes"));
        List<String> entries = new ArrayList<>();
        for (Class<?> type : List.of(AsyncApiRule.class, AsyncAPI.class, JsonNode.class, JsonValue.class, JsonProcessingException.class))
            entries.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "Tests require a JDK");
        assertEquals(0, compiler.run(null, null, null, "--release", "17", "-classpath", String.join(java.io.File.pathSeparator, entries),
                "-d", classes.toString(), source.toString()));
        Path jar = directory.resolve("extension.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("external/ExternalRule.class"));
            output.write(Files.readAllBytes(classes.resolve("external/ExternalRule.class")));
            output.closeEntry();
        }
        assertThrows(ClassNotFoundException.class, () -> Class.forName("external.ExternalRule"));
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var loader = new URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
            var result = new ExtensionExecutor(loader).validate(valid(), List.of(new ExtensionSpec("external.ExternalRule", Map.of("message", "Loaded"))));
            assertEquals("Loaded", result.diagnostics().get(0).message());
            assertEquals("/info/title", result.diagnostics().get(0).pointer());
            assertSame(previous, Thread.currentThread().getContextClassLoader());
            var externalFailure = assertThrows(ExtensionExecutionException.class, () -> new ExtensionExecutor(loader).validate(valid(),
                    List.of(new ExtensionSpec("external.ExternalRule", Map.of("fail", "true")))));
            assertEquals("External failure", externalFailure.getCause().getMessage());
            assertSame(previous, Thread.currentThread().getContextClassLoader());
        }
        var failure = assertThrows(ExtensionExecutionException.class, () -> executor.validate(valid(), List.of(spec(FailingRule.class))));
        assertInstanceOf(IOException.class, failure.getCause());
        assertSame(previous, Thread.currentThread().getContextClassLoader());
    }

    public static class ErrorRule implements AsyncApiRule {
        public List<Diagnostic> validate(ExtensionContext c) { return List.of(c.diagnostic("test.error", Severity.ERROR, "error", "/info/title")); }
    }
    public static class WarningRule implements AsyncApiRule {
        public List<Diagnostic> validate(ExtensionContext c) { return List.of(c.diagnostic("test.warning", Severity.WARNING, "warning", "/info/title")); }
    }
    public static class RequiredParameterRule implements AsyncApiRule {
        public List<Diagnostic> validate(ExtensionContext c) { c.requireParameter("missing"); return List.of(); }
    }
    public static class FailingRule implements AsyncApiRule {
        public List<Diagnostic> validate(ExtensionContext c) throws IOException { throw new IOException("rule failed"); }
    }
    public static class NoPublicConstructorRule implements AsyncApiRule {
        private NoPublicConstructorRule() { }
        public List<Diagnostic> validate(ExtensionContext c) { return List.of(); }
    }
    public static class FailingConstructorRule implements AsyncApiRule {
        public FailingConstructorRule() throws IOException { throw new IOException("constructor failed"); }
        public List<Diagnostic> validate(ExtensionContext c) { return List.of(); }
    }
    public static class NullRule implements AsyncApiRule {
        public List<Diagnostic> validate(ExtensionContext c) { return null; }
    }
    public static class AbsoluteDiagnosticRule implements AsyncApiRule {
        public List<Diagnostic> validate(ExtensionContext c) { return List.of(new Diagnostic("invalid", Severity.ERROR, "invalid", "C:/absolute.yaml", 1, 1, "", List.of())); }
    }
    public static class MutatingRule implements AsyncApiRule {
        public List<Diagnostic> validate(ExtensionContext c) {
            Objects.requireNonNull(c.document().model().getInfo()).setTitle("Changed");
            ((ObjectNode)c.document().source()).removeAll();
            ((ObjectNode)c.document().references().resolve("/channels/events").orElseThrow().node()).removeAll();
            assertThrows(UnsupportedOperationException.class, () -> c.parameters().put("key", "value"));
            return List.of();
        }
    }
    public static class OriginalRule implements AsyncApiRule {
        public List<Diagnostic> validate(ExtensionContext c) {
            assertEquals("Original", Objects.requireNonNull(c.document().model().getInfo()).getTitle());
            assertTrue(c.document().source().has("channels"));
            assertEquals("events", c.document().references().resolve("/channels/events").orElseThrow().node().path("address").asText());
            return List.of();
        }
    }
    public static class SnapshotRule implements AsyncApiRule {
        public List<Diagnostic> validate(ExtensionContext c) throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            assertEquals(mapper.readTree(c.requireParameter("expected")),
                    mapper.readTree(mapper.writeValueAsString(c.document().model())));
            return List.of();
        }
    }
    public static class TextGenerator implements AsyncApiGenerator {
        public List<Diagnostic> generate(GeneratorContext c) throws IOException {
            c.output().writeUtf8(c.requireParameter("path"), c.requireParameter("value")); return List.of();
        }
    }
    public static class TitleGenerator implements AsyncApiGenerator {
        public List<Diagnostic> generate(GeneratorContext c) throws IOException {
            c.output().writeUtf8("title.txt", Objects.requireNonNull(c.document().model().getInfo()).getTitle()); return List.of();
        }
    }
    public static class FailingGenerator implements AsyncApiGenerator {
        public List<Diagnostic> generate(GeneratorContext c) throws IOException { throw new IOException("generator failed"); }
    }
    public static class ErrorGenerator implements AsyncApiGenerator {
        public List<Diagnostic> generate(GeneratorContext c) throws IOException {
            c.output().writeUtf8("partial.txt", "partial");
            return List.of(c.diagnostic("test.generator", Severity.ERROR, "Generation failed", ""));
        }
    }
}
