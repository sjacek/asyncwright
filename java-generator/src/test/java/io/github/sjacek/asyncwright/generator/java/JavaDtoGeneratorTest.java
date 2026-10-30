package io.github.sjacek.asyncwright.generator.java;

import io.github.sjacek.asyncwright.api.ExtensionSpec;
import io.github.sjacek.asyncwright.parser.AsyncApiParser;
import io.github.sjacek.asyncwright.parser.ExtensionExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.*;

/** Compile and exercise the generated public API instead of testing only renderer substrings. */
class JavaDtoGeneratorTest {
    @TempDir
    Path directory;
    private static final String HEADER = """
            asyncapi: 3.0.0
            info: {title: DTO generator, version: 1.0.0}
            components:
              messages:
                changed:
                  payload: {$ref: '#/components/schemas/Event'}
              schemas:
            """;

    private static final String SCHEMAS = """
                Event:
                  type: object
                  additionalProperties: false
                  required: [id, count, enabled, items]
                  properties:
                    id: {type: string, format: uuid}
                    count: {type: integer, format: int64}
                    optionalCount: {type: integer, format: int64}
                    enabled: {type: boolean}
                    amount: {type: number}
                    status: {$ref: '#/components/schemas/Status'}
                    items: {type: array, items: {$ref: '#/components/schemas/Detail'}}
                    tags: {type: array, uniqueItems: true, items: {type: string}}
                    note: {type: [string, 'null']}
                Detail:
                  type: object
                  additionalProperties: false
                  properties:
                    parent: {$ref: '#/components/schemas/Event'}
                    value: {type: string}
                Status:
                  type: [string, 'null']
                  enum: [READY, FAILED, null]
                HeaderOnly:
                  type: object
                  properties:
                    header: {type: string}
            """;

    @Test
    void emitsCompilableBeansWithNullableEnumsCollectionsAndConcreteRecursion() throws Exception {
        var result = generate(HEADER + SCHEMAS, directory);
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertEquals(3, result.generatedFiles().size());
        assertFalse(Files.exists(directory.resolve("sample/dto/HeaderOnly.java")));
        try (var loader = compile(directory)) {
            Class<?> event = loader.loadClass("sample.dto.Event");
            assertEquals(long.class, event.getMethod("getCount").getReturnType());
            assertEquals(Long.class, event.getMethod("getOptionalCount").getReturnType());
            assertEquals(boolean.class, event.getMethod("getEnabled").getReturnType());
            assertEquals(Double.class, event.getMethod("getAmount").getReturnType());
            assertEquals(java.util.UUID.class, event.getMethod("getId").getReturnType());
            assertEquals(List.class, event.getMethod("getItems").getReturnType());
            assertEquals(java.util.Set.class, event.getMethod("getTags").getReturnType());
            Class<?> status = loader.loadClass("sample.dto.Status");
            assertEquals(2, status.getEnumConstants().length);
            Object ready = status.getMethod("fromValue", String.class).invoke(null, "READY");
            assertEquals("READY", ready.toString());
            Object bean = event.getConstructor().newInstance();
            event.getMethod("setStatus", status).invoke(bean, ready);
            assertSame(ready, event.getMethod("getStatus").invoke(bean));
            event.getMethod("setStatus", status).invoke(bean, new Object[] {null});
            assertNull(event.getMethod("getStatus").invoke(bean));
            Class<?> detail = loader.loadClass("sample.dto.Detail");
            assertEquals(event, detail.getMethod("getParent").getReturnType());
        }
    }

    @Test
    void followsRootAliasesWithoutChangingSourceOrGeneratingAliasClasses() throws Exception {
        String input = (HEADER + SCHEMAS).replace(
                "payload: {$ref: '#/components/schemas/Event'}",
                "payload: {$ref: '#/components/schemas/Alias'}")
                + "    Alias: {$ref: '#/components/schemas/Event'}\n";
        var parsed = new AsyncApiParser().parse("alias.yaml", input);
        assertTrue(parsed.successful(), parsed.diagnostics()::toString);
        var original = parsed.document().orElseThrow().source().deepCopy();
        var result = new ExtensionExecutor().generate(parsed, List.of(),
                List.of(new ExtensionSpec(JavaDtoGenerator.class.getName(), Map.of(JavaDtoGenerator.PACKAGE_NAME, "sample.dto"))), directory);
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertEquals(original, parsed.document().orElseThrow().source());
        assertEquals(3, result.generatedFiles().size());
        assertFalse(Files.exists(directory.resolve("sample/dto/Alias.java")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"class", "a-b", "uRL", "a/b"})
    void rejectsNamesThatWouldChangeWirePropertiesBeforeWritingAnyFiles(String name) throws Exception {
        String input = HEADER + SCHEMAS.replace("    note:", "    " + name + ":");
        var result = generate(input, directory);
        assertFalse(result.successful());
        assertEquals("java-dto.name", result.diagnostics().get(0).ruleId());
        assertTrue(result.generatedFiles().isEmpty());
        assertTrue(result.diagnostics().get(0).pointer().contains("/properties/"));
    }

    @Test
    void rejectsCompositionsWithAProfileDiagnosticAndNoPartialModels() throws Exception {
        String input = HEADER + SCHEMAS.replace("type: [string, 'null']\n      enum:",
                "type: [string, 'null']\n      oneOf: [{type: string}, {type: 'null'}]\n      enum:");
        var result = generate(input, directory);
        assertFalse(result.successful(), result.diagnostics()::toString);
        assertEquals("java-dto.unsupported", result.diagnostics().get(0).ruleId());
        assertEquals("/components/schemas/Status/oneOf", result.diagnostics().get(0).pointer());
        assertTrue(result.generatedFiles().isEmpty());
    }

    @Test
    void producesIdenticalBytesForRepeatedInvocations() throws Exception {
        var first = generate(HEADER + SCHEMAS, directory.resolve("first"));
        var second = generate(HEADER + SCHEMAS, directory.resolve("second"));
        assertTrue(first.successful(), first.diagnostics()::toString);
        assertEquals(first.generatedFiles(), second.generatedFiles());
        for (String path : first.generatedFiles())
            assertArrayEquals(Files.readAllBytes(directory.resolve("first").resolve(path)),
                    Files.readAllBytes(directory.resolve("second").resolve(path)));
    }

    private static io.github.sjacek.asyncwright.api.ExtensionResult generate(String source, Path output) throws Exception {
        return new ExtensionExecutor().generate(new AsyncApiParser().parse("schema.yaml", source), List.of(),
                List.of(new ExtensionSpec(JavaDtoGenerator.class.getName(),
                        Map.of(JavaDtoGenerator.PACKAGE_NAME, "sample.dto"))), output);
    }

    static URLClassLoader compile(Path output) throws Exception {
        Path classes = output.resolve("classes");
        Files.createDirectories(classes);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK");
        // Match the generator's UTF-8 contract also on JDK 17 hosts with a legacy default charset.
        try (var manager = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8);
             var paths = Files.walk(output)) {
            List<Path> sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
            assertTrue(compiler.getTask(null, manager, null, List.of("--release", "17", "-d", classes.toString()), null,
                    manager.getJavaFileObjectsFromPaths(sources)).call());
        }
        return new URLClassLoader(new java.net.URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }
}
