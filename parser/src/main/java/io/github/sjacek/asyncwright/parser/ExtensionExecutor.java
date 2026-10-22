package io.github.sjacek.asyncwright.parser;

import com.asyncapi.v3._0_0.model.AsyncAPI;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sjacek.asyncwright.api.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Executes extensions on the calling thread; the Gradle host supplies a worker classloader in F2. */
public final class ExtensionExecutor {
    private static final ObjectMapper MAPPER = ModelMapping.mapper();
    private final ClassLoader loader;

    public ExtensionExecutor(ClassLoader loader) { this.loader = Objects.requireNonNull(loader, "loader"); }
    public ExtensionExecutor() { this(ExtensionExecutor.class.getClassLoader()); }

    public ExtensionResult validate(ParseResult parsed, List<ExtensionSpec> rules) throws ExtensionExecutionException {
        return execute(parsed, rules, List.of(), null);
    }

    public ExtensionResult generate(ParseResult parsed, List<ExtensionSpec> rules, List<ExtensionSpec> generators,
                                    Path outputDirectory) throws ExtensionExecutionException {
        return execute(parsed, rules, generators, Objects.requireNonNull(outputDirectory, "outputDirectory"));
    }

    private ExtensionResult execute(ParseResult parsed, List<ExtensionSpec> rules, List<ExtensionSpec> generators,
                                    Path outputDirectory) throws ExtensionExecutionException {
        List<Diagnostic> diagnostics = new ArrayList<>(parsed.diagnostics());
        if (!parsed.successful()) return new ExtensionResult(diagnostics, List.of());
        AsyncApiDocument document = parsed.document().orElseThrow(() ->
                new IllegalArgumentException("A successful parse result must contain a document"));
        for (ExtensionSpec spec : List.copyOf(rules))
            diagnostics.addAll(invoke(spec, ExtensionExecutionException.Kind.RULE, AsyncApiRule.class,
                    rule -> rule.validate(new RuleInput(view(document), spec.parameters()))));
        if (hasErrors(diagnostics) || generators.isEmpty()) return new ExtensionResult(diagnostics, List.of());
        DirectoryOutput output = new DirectoryOutput(outputDirectory);
        for (ExtensionSpec spec : List.copyOf(generators)) {
            diagnostics.addAll(invoke(spec, ExtensionExecutionException.Kind.GENERATOR, AsyncApiGenerator.class,
                    generator -> generator.generate(new GeneratorInput(view(document), spec.parameters(), output))));
            if (hasErrors(diagnostics)) break;
        }
        return new ExtensionResult(diagnostics, output.writtenFiles());
    }

    private static AsyncApiDocumentView view(AsyncApiDocument document) throws JsonProcessingException {
        // Recreate from validated input: a model round-trip can change numeric representations.
        AsyncAPI model = MAPPER.treeToValue(document.references().mappingTree("", document.source()), AsyncAPI.class);
        return new AsyncApiDocumentView() {
            @Override public AsyncAPI model() { return model; }
            @Override public JsonNode source() { return document.source(); }
            @Override public SourceLocation location(String pointer) { return document.location(pointer); }
            @Override public AsyncApiReferences references() { return document.references(); }
        };
    }

    private static boolean hasErrors(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }

    @FunctionalInterface
    private interface Invocation<T> { List<Diagnostic> run(T extension) throws Exception; }

    private <T> List<Diagnostic> invoke(ExtensionSpec spec, ExtensionExecutionException.Kind kind,
                                       Class<T> contract, Invocation<T> invocation) throws ExtensionExecutionException {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> type = Class.forName(spec.className(), false, loader);
            if (!contract.isAssignableFrom(type)) throw new IllegalArgumentException(
                    spec.className() + " must implement " + contract.getName());
            T extension = contract.cast(type.getConstructor().newInstance());
            List<Diagnostic> diagnostics = List.copyOf(invocation.run(extension));
            for (Diagnostic diagnostic : diagnostics) {
                if (Objects.requireNonNull(diagnostic.ruleId(), "ruleId").isBlank())
                    throw new IllegalArgumentException("Expected a diagnostic rule ID");
                Objects.requireNonNull(diagnostic.severity(), "severity");
                Objects.requireNonNull(diagnostic.message(), "message");
                validateLocation(new SourceLocation(diagnostic.file(), diagnostic.line(), diagnostic.column(), diagnostic.pointer()));
                for (SourceLocation related : diagnostic.related()) validateLocation(related);
            }
            return diagnostics;
        } catch (InvocationTargetException exception) {
            throw new ExtensionExecutionException(spec.className(), kind, exception.getCause());
        } catch (Exception | LinkageError exception) {
            throw new ExtensionExecutionException(spec.className(), kind, exception);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static void validateLocation(SourceLocation location) {
        DirectoryOutput.relativePath(location.file());
        if (location.file().contains("\\") || location.line() < 1 || location.column() < 1
                || !(location.pointer().isEmpty() || location.pointer().startsWith("/")))
            throw new IllegalArgumentException("Expected a project-relative, one-based diagnostic location");
    }

    private record RuleInput(AsyncApiDocumentView document, Map<String, String> parameters) implements ExtensionContext { }
    private record GeneratorInput(AsyncApiDocumentView document, Map<String, String> parameters,
                                   GeneratedFiles output) implements GeneratorContext { }
}
