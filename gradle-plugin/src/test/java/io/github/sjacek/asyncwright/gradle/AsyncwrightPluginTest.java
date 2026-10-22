package io.github.sjacek.asyncwright.gradle;

import io.github.sjacek.asyncwright.api.AsyncApiRule;
import com.asyncapi.v3._0_0.model.channel.Channel;
import tools.jackson.databind.json.JsonMapper;
import org.gradle.testkit.runner.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.gradle.testkit.runner.TaskOutcome.*;

class AsyncwrightPluginTest {
    private static final String API_PACKAGE = AsyncApiRule.class.getPackageName();
    @TempDir Path temporary;
    private static final String DOCUMENT = """
            asyncapi: 3.0.0
            info:
              title: TestKit
              version: 1.0.0
            defaultContentType: application/json
            servers:
              broker:
                $ref: '#/components/servers/broker'
            channels:
              events:
                $ref: '#/components/channels/events'
            components:
              servers:
                broker:
                  host: localhost
                  protocol: amqp
              channels:
                events:
                  address: events.created
            """;
    private static final String PLUGIN = "plugins { id '" + AsyncwrightPlugin.PLUGIN_ID + "' }\n";
    private static final String SPEC = """
            asyncwright {
                specs {
                    events {
                        file = layout.projectDirectory.file('contracts/events.yaml')
                        rules {
                            channelAddress ~/^events[.].+$/
                            requireDefaultContentType 'application/json'
                            requireProtocol 'amqp'
                        }
                    }
                }
            }
            """;
    private static final String REGISTRATION = """
            dependencies { asyncwrightExtensions project(':extensions') }
            asyncwright.specs.events {
                rules { rule('fixture.ProjectRule') { parameters.put('message', 'first') } }
                generators { catalog { className = 'fixture.Catalog'; parameters.put('name', 'channels.txt') } }
            }
            """;

    @Test void registersLazyTasksAndConnectsValidationToCheck() throws Exception {
        Path root = fixture("consumer", PLUGIN + SPEC);
        assertEquals(SUCCESS, outcome(run(root, "check"), ":asyncApiValidateEvents"));
        assertEquals("[ ]\n", read(root, "build/reports/asyncapi/events.json"));
        assertEquals("", read(root, "build/reports/asyncapi/events.txt"));
        Files.delete(root.resolve("contracts/events.yaml"));
        assertNull(run(root, "help").task(":asyncApiValidateEvents"));
        assertEquals(FAILED, outcome(fail(root, "check"), ":asyncApiValidateEvents"));
    }

    @Test void reportsAllDeclarativeFailuresAtResolvedDefinitionsWithRelatedUses() throws Exception {
        Path root = fixture("invalid", PLUGIN + SPEC);
        write(root, "contracts/events.yaml", DOCUMENT.replace("application/json", "text/plain")
                .replace("protocol: amqp", "protocol: mqtt").replace("address: events.created", "address: bad"));
        BuildResult result = fail(root, "check");
        String json = read(root, "build/reports/asyncapi/events.json");
        assertTrue(json.contains("project.channel-address"));
        assertTrue(json.contains("project.default-content-type"));
        assertTrue(json.contains("project.protocol"));
        assertTrue(json.contains("/components/channels/events/address"));
        assertTrue(json.contains("/channels/events/$ref"));
        assertFalse(json.contains(root.toString().replace("\\", "\\\\")));
        assertTrue(result.getOutput().contains("3 error(s)"));
        assertTrue(result.getOutput().contains(root.resolve("contracts/events.yaml").toString()));
    }

    @Test void schemaMetadataDoesNotWarnWhileProjectDiagnosticsRemainVisible() throws Exception {
        Path root = fixture("schema-metadata", PLUGIN + SPEC);
        projectExtension(root);
        append(root, "build.gradle", REGISTRATION);
        // A fresh daemon/classloader prevents NetworkNT's once-per-keyword cache from hiding the warning.
        // Keep its files outside @TempDir because Windows daemons retain file locks after a build.
        File testKit = new File(System.getProperty("asyncwright.testKitDir"), "schema-metadata-" + UUID.randomUUID());
        BuildResult result = runner(root, "check", "asyncApiGenerateEventsCatalog").withTestKitDir(testKit).build();
        assertEquals(SUCCESS, outcome(result, ":asyncApiValidateEvents"));
        assertEquals(SUCCESS, outcome(result, ":asyncApiGenerateEventsCatalog"));
        assertFalse(result.getOutput().contains("Unknown keyword example"), result::getOutput);
        assertTrue(result.getOutput().contains("fixture.warning"), result::getOutput);
        assertTrue(read(root, "build/reports/asyncapi/events.json").contains("fixture.warning"));
        assertEquals("events.created", read(root, "build/generated/asyncapi/events/catalog/channels.txt"));
    }

    @Test void parserErrorsSkipProjectRulesAndKeepStructuralReports() throws Exception {
        Path root = fixture("syntax", PLUGIN + SPEC + "asyncwright.specs.events.rules { rule 'does.not.Exist' }\n");
        write(root, "contracts/events.yaml", "asyncapi: 3.0.0\ninfo: [\n");
        BuildResult result = fail(root, "check");
        assertTrue(read(root, "build/reports/asyncapi/events.json").contains("syntax"));
        assertFalse(result.getOutput().contains("ClassNotFoundException"));
    }

    @Test void reusesConfigurationAndBuildCachesAndRelocatesReportsByteForByte() throws Exception {
        Path root = fixture("A", PLUGIN + SPEC);
        projectExtension(root);
        append(root, "build.gradle", REGISTRATION);
        assertEquals(SUCCESS, outcome(run(root, "asyncApiValidate"), ":asyncApiValidateEvents"));
        byte[] json = Files.readAllBytes(root.resolve("build/reports/asyncapi/events.json"));
        byte[] text = Files.readAllBytes(root.resolve("build/reports/asyncapi/events.txt"));
        BuildResult second = run(root, "asyncApiValidate");
        assertEquals(UP_TO_DATE, outcome(second, ":asyncApiValidateEvents"));
        assertTrue(second.getOutput().contains("Reusing configuration cache"));
        BuildResult cached = run(root, "clean", "asyncApiValidate");
        assertEquals(FROM_CACHE, outcome(cached, ":asyncApiValidateEvents"));
        Path relocated = temporary.resolve("B");
        copySources(root, relocated);
        BuildResult result = run(relocated, "asyncApiValidate");
        assertEquals(FROM_CACHE, outcome(result, ":asyncApiValidateEvents"));
        assertArrayEquals(json, Files.readAllBytes(relocated.resolve("build/reports/asyncapi/events.json")));
        assertArrayEquals(text, Files.readAllBytes(relocated.resolve("build/reports/asyncapi/events.txt")));
    }

    @Test void changingTheRelativeSourceDirectoryInvalidatesTheReport() throws Exception {
        Path root = fixture("rename", PLUGIN + SPEC);
        projectExtension(root);
        append(root, "build.gradle", REGISTRATION);
        run(root, "asyncApiValidate");
        write(root, "other/events.yaml", DOCUMENT);
        write(root, "build.gradle", read(root, "build.gradle").replace("contracts/events.yaml", "other/events.yaml"));
        assertEquals(SUCCESS, outcome(run(root, "asyncApiValidate"), ":asyncApiValidateEvents"));
        assertTrue(read(root, "build/reports/asyncapi/events.json").contains("other/events.yaml"));
    }

    @Test void extensionsGenerateInWorkersAndClasspathAndParametersInvalidateCache() throws Exception {
        Path root = fixture("extensions", PLUGIN + SPEC);
        projectExtension(root);
        append(root, "build.gradle", REGISTRATION);
        run(root, "asyncApiValidate", "asyncApiGenerateEventsCatalog");
        String output = "build/generated/asyncapi/events/catalog/channels.txt";
        assertEquals("events.created", read(root, output));
        BuildResult repeat = run(root, "asyncApiValidate", "asyncApiGenerateEventsCatalog");
        assertEquals(UP_TO_DATE, outcome(repeat, ":asyncApiGenerateEventsCatalog"));
        assertTrue(repeat.getOutput().contains("Reusing configuration cache"));
        assertEquals(FROM_CACHE, outcome(run(root, "clean", "asyncApiGenerateEventsCatalog"), ":asyncApiGenerateEventsCatalog"));
        write(root, "extensions/src/main/java/fixture/ProjectRule.java", rule().replace("fixture.warning", "fixture.changed"));
        BuildResult changed = run(root, "asyncApiValidate", "asyncApiGenerateEventsCatalog");
        assertEquals(SUCCESS, outcome(changed, ":asyncApiValidateEvents"));
        assertEquals(SUCCESS, outcome(changed, ":asyncApiGenerateEventsCatalog"));
        assertTrue(read(root, "build/reports/asyncapi/events.json").contains("fixture.changed"));
        write(root, "build.gradle", read(root, "build.gradle").replace("'first'", "'second'")
                .replace("'channels.txt'", "'renamed.txt'"));
        run(root, "asyncApiValidate", "asyncApiGenerateEventsCatalog");
        assertTrue(read(root, "build/reports/asyncapi/events.json").contains("second"));
        assertFalse(Files.exists(root.resolve(output)), "obsolete generator output removed");
        assertEquals("events.created", read(root, "build/generated/asyncapi/events/catalog/renamed.txt"));
    }

    @Test void jackson3AndBuildScriptOnlyClassesAreHiddenFromWorker() throws Exception {
        String hostJars = files(List.of(location(JsonMapper.class),
                location(tools.jackson.core.JsonToken.class), location(com.fasterxml.jackson.annotation.JsonProperty.class)));
        Path root = fixture("isolation", "buildscript { dependencies { classpath files(" + hostJars + ") } }\n"
                + PLUGIN + "assert new " + JsonMapper.class.getName() + "().version().majorVersion == 3\n" + SPEC);
        projectExtension(root);
        append(root, "build.gradle", REGISTRATION);
        write(root, "extensions/src/main/java/fixture/ProjectRule.java", rule().replace("return List.of(", """
                if (new com.fasterxml.jackson.databind.ObjectMapper().version().getMajorVersion() != 2)
                    throw new IllegalStateException("Wrong Jackson in worker");
                if (Thread.currentThread().getContextClassLoader() != getClass().getClassLoader())
                    throw new IllegalStateException("Wrong context loader");
                try { Class.forName("%s", false, getClass().getClassLoader());
                    throw new IllegalStateException("Host Jackson leaked");
                } catch (ClassNotFoundException expected) { }
                return List.of(
                """.formatted(JsonMapper.class.getName())));
        assertEquals(SUCCESS, outcome(run(root, "check"), ":asyncApiValidateEvents"));
    }

    @Test void failedGeneratorPreservesCauseAndPublishesNoPartialOrStaleFiles() throws Exception {
        Path root = fixture("generator-failure", PLUGIN + SPEC);
        projectExtension(root);
        append(root, "build.gradle", REGISTRATION);
        run(root, "asyncApiGenerateEventsCatalog");
        write(root, "extensions/src/main/java/fixture/Catalog.java", generator().replace("return List.of();",
                "throw new IllegalStateException(\"generator-root-cause\");"));
        BuildResult failed = fail(root, "asyncApiGenerateEventsCatalog");
        assertTrue(failed.getOutput().contains("generator-root-cause"));
        assertFalse(Files.exists(root.resolve("build/generated/asyncapi/events/catalog")));
        assertFalse(Files.exists(root.resolve("build/tmp/asyncApiGenerateEventsCatalog/staging")));
    }

    @Test void validationFailurePreventsGenerationAndDeletesPreviousOutput() throws Exception {
        Path root = fixture("rules-failure", PLUGIN + SPEC);
        projectExtension(root);
        append(root, "build.gradle", REGISTRATION);
        run(root, "asyncApiGenerateEventsCatalog");
        write(root, "extensions/src/main/java/fixture/ProjectRule.java", rule().replace("Severity.WARNING", "Severity.ERROR"));
        fail(root, "asyncApiGenerateEventsCatalog");
        assertFalse(Files.exists(root.resolve("build/generated/asyncapi/events/catalog")));
    }

    @Test void includedBuildCanSupplyExtensions() throws Exception {
        Path root = fixture("composite", PLUGIN + SPEC);
        projectExtension(root);
        write(root, "settings.gradle", settings("includeBuild('extensions')\n"));
        write(root, "extensions/settings.gradle", "rootProject.name = 'extensions'\n");
        append(root, "extensions/build.gradle", "group = 'fixture'; version = '1'\n");
        append(root, "build.gradle", REGISTRATION.replace("project(':extensions')", "'fixture:extensions:1'"));
        assertEquals(SUCCESS, outcome(run(root, "asyncApiGenerateEventsCatalog"), ":asyncApiGenerateEventsCatalog"));
        assertEquals("events.created", read(root, "build/generated/asyncapi/events/catalog/channels.txt"));
    }

    @Test void modelTaskApiCanBeRegisteredDirectly() throws Exception {
        Path root = fixture("model-task", PLUGIN);
        projectExtension(root);
        append(root, "build.gradle", """
                dependencies { asyncwrightExtensions project(':extensions') }
                tasks.register('catalog', %s) {
                    file = layout.projectDirectory.file('contracts/events.yaml')
                    extensionClasspath.from(configurations.asyncwrightExtensions)
                    generatorClass = 'fixture.Catalog'
                    parameters.put('name', 'direct.txt')
                    outputDirectory = layout.buildDirectory.dir('direct')
                }
                """.formatted(AsyncApiGenerate.class.getName()));
        run(root, "catalog");
        assertEquals("events.created", read(root, "build/direct/direct.txt"));
    }

    @Test void missingExtensionClassFailsExplicitly() throws Exception {
        Path root = fixture("missing-class", PLUGIN + SPEC + "asyncwright.specs.events.rules { rule 'does.not.Exist' }\n");
        assertTrue(fail(root, "check").getOutput().contains("ClassNotFoundException: does.not.Exist"));
    }

    @Test void registersRuleClassesWithAndWithoutParametersIncludingNestedBinaryNames() throws Exception {
        Path root = fixture("typed-rules", PLUGIN + SPEC);
        projectExtension(root);
        write(root, "extensions/src/main/java/fixture/ProjectRule.java", """
                package fixture;
                import %s.*;
                import java.util.List;
                public class ProjectRule implements AsyncApiRule {
                    public List<Diagnostic> validate(ExtensionContext context) {
                        return List.of(context.diagnostic("fixture.typed-rule", Severity.WARNING,
                            context.parameters().getOrDefault("message", "default rule"), "/info/title"));
                    }
                    public static class DefaultRule extends ProjectRule { }
                }
                """.formatted(API_PACKAGE));
        // Class literals require a JAR already available when the build script is configured.
        run(root, ":extensions:jar");
        List<Path> hostClasspath = new ArrayList<>(pluginClasspath());
        hostClasspath.add(root.resolve("extensions/build/libs/extensions.jar"));
        write(root, "build.gradle", "buildscript { dependencies { classpath files(" + files(hostClasspath) + ") } }\n"
                + PLUGIN + SPEC + """
                dependencies { asyncwrightExtensions project(':extensions') }
                asyncwright.specs.events.rules {
                    rule(fixture.ProjectRule.class) { parameters.put('message', 'typed rule') }
                    rule(fixture.ProjectRule.DefaultRule.class)
                }
                """);
        assertEquals(SUCCESS, outcome(run(root, "check"), ":asyncApiValidateEvents"));
        String report = read(root, "build/reports/asyncapi/events.json");
        assertTrue(report.contains("typed rule"));
        assertTrue(report.contains("default rule"));
        BuildResult repeated = run(root, "check");
        assertEquals(UP_TO_DATE, outcome(repeated, ":asyncApiValidateEvents"));
        assertTrue(repeated.getOutput().contains("Reusing configuration cache"));
    }

    @Test void rejectsCollidingSpecTaskNames() throws Exception {
        Path root = fixture("colliding", PLUGIN + "asyncwright.specs { events {}; Events {} }\n");
        assertTrue(fail(root, "help").getOutput().contains("Colliding AsyncAPI task name"));
    }

    @Test void packagedPluginContainsParserApiAndSchemasWithoutASeparateParserArtifact() throws Exception {
        Path root = fixture("packaged", PLUGIN + SPEC);
        List<File> classpath = new ArrayList<>(pluginClasspath().stream().filter(path -> path.toString().endsWith(".jar"))
                .map(Path::toFile).toList());
        classpath.add(new File(System.getProperty("asyncwright.pluginJar")));
        projectExtension(root);
        write(root, "extensions/build.gradle", "plugins { id 'java-library' }\ndependencies { compileOnly files("
                + files(classpath.stream().map(File::toPath).toList()) + ") }\n");
        append(root, "build.gradle", REGISTRATION);
        assertEquals(SUCCESS, outcome(runner(root, "check", "asyncApiGenerateEventsCatalog").withPluginClasspath(classpath).build(), ":asyncApiValidateEvents"));
        assertEquals("events.created", read(root, "build/generated/asyncapi/events/catalog/channels.txt"));
        try (var jar = new java.util.jar.JarFile(System.getProperty("asyncwright.pluginJar"))) {
            assertNotNull(jar.getEntry(AsyncApiRule.class.getName().replace('.', '/') + ".class"));
            assertNotNull(jar.getEntry("schemas/asyncapi/3.0.0.json"));
            assertNotNull(jar.getEntry("META-INF/licenses/spec-json-schemas/NOTICE"));
            assertNotNull(jar.getEntry("META-INF/LICENSE"));
        }
    }

    @Test void kotlinDslCanConfigureSpecsAndRules() throws Exception {
        Path root = fixture("kotlin", "");
        Files.delete(root.resolve("build.gradle"));
        write(root, "build.gradle.kts", """
                plugins { id("io.github.sjacek.asyncwright") }
                asyncwright {
                    specs {
                        register("events") {
                            file.set(layout.projectDirectory.file("contracts/events.yaml"))
                            rules {
                                channelAddress("^events[.].+$")
                                requireProtocol("amqp")
                            }
                        }
                    }
                }
                """);
        assertEquals(SUCCESS, outcome(run(root, "check"), ":asyncApiValidateEvents"));
    }

    @Test void builtinRuleConfigurationAndInputContentsInvalidateCache() throws Exception {
        Path root = fixture("rule-inputs", PLUGIN + SPEC);
        run(root, "check");
        write(root, "build.gradle", read(root, "build.gradle").replace("'amqp'", "'mqtt'"));
        assertTrue(fail(root, "check").getOutput().contains("project.protocol"));
        write(root, "contracts/events.yaml", DOCUMENT.replace("protocol: amqp", "protocol: mqtt"));
        assertEquals(SUCCESS, outcome(run(root, "check"), ":asyncApiValidateEvents"));
    }

    @Test void customRuleFailureKeepsItsOriginalCauseAndRemovesStaleReports() throws Exception {
        Path root = fixture("rule-exception", PLUGIN + SPEC);
        projectExtension(root);
        append(root, "build.gradle", REGISTRATION);
        run(root, "check");
        write(root, "extensions/src/main/java/fixture/ProjectRule.java", """
                package fixture;
                import %s.*;
                import java.util.List;
                public class ProjectRule implements AsyncApiRule {
                    public List<Diagnostic> validate(ExtensionContext context) {
                        throw new IllegalStateException("rule-root-cause");
                    }
                }
                """.formatted(API_PACKAGE));
        assertTrue(fail(root, "check").getOutput().contains("rule-root-cause"));
        assertFalse(Files.exists(root.resolve("build/reports/asyncapi/events.json")));
    }

    @Test void rejectsGeneratorOutputOutsideBuildEvenOnCacheRestore() throws Exception {
        Path root = fixture("unsafe-output", PLUGIN + SPEC);
        projectExtension(root);
        append(root, "build.gradle", REGISTRATION);
        run(root, "asyncApiGenerateEventsCatalog"); // Seed a successful cache entry with identical inputs.
        append(root, "build.gradle", """
                tasks.named('asyncApiGenerateEventsCatalog') { outputDirectory = layout.projectDirectory.dir('generated') }
                """);
        write(root, "generated/keep.txt", "existing consumer file");
        BuildResult result = fail(root, "asyncApiGenerateEventsCatalog");
        assertTrue(result.getOutput().contains("dedicated directory below buildDirectory"), result.getOutput());
        assertEquals(DOCUMENT, read(root, "contracts/events.yaml"));
        assertEquals("existing consumer file", read(root, "generated/keep.txt"));
        assertFalse(Files.exists(root.resolve("generated/channels.txt")), "cache must not restore into a forbidden output");
        assertTrue(Files.exists(root.resolve("build.gradle")));
    }

    private Path fixture(String name, String build) throws IOException {
        Path root = temporary.resolve(name);
        write(root, "settings.gradle", settings(""));
        write(root, "gradle.properties", "org.gradle.jvmargs=-Xmx384m -Dfile.encoding=UTF-8\norg.gradle.workers.max=2\n");
        write(root, "build.gradle", build);
        write(root, "contracts/events.yaml", DOCUMENT);
        return root;
    }
    private String settings(String extra) {
        return "rootProject.name = 'fixture'\n" + extra + "buildCache { local { directory = '"
                + slash(temporary.resolve("shared-cache")) + "' } }\n";
    }
    private void projectExtension(Path root) throws Exception {
        append(root, "settings.gradle", "include 'extensions'\n");
        write(root, "extensions/build.gradle", "plugins { id 'java-library' }\njava { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }\n"
                + "dependencies { compileOnly files(" + files(pluginClasspath()) + ") }\n");
        write(root, "extensions/src/main/java/fixture/ProjectRule.java", rule());
        write(root, "extensions/src/main/java/fixture/Catalog.java", generator());
    }
    private static String rule() {
        return """
                package fixture;
                import %s.*;
                import java.util.List;
                public class ProjectRule implements AsyncApiRule {
                    public List<Diagnostic> validate(ExtensionContext context) throws Exception {
                        return List.of(context.diagnostic("fixture.warning", Severity.WARNING,
                            context.requireParameter("message"), "/info/title"));
                    }
                }
                """.formatted(API_PACKAGE);
    }
    private static String generator() {
        return """
                package fixture;
                import %s.*;
                import %s;
                import java.util.List;
                public class Catalog implements AsyncApiGenerator {
                    public List<Diagnostic> generate(GeneratorContext context) throws Exception {
                        Channel channel = context.document().references().resolve("/channels/events", Channel.class).orElseThrow();
                        context.output().writeUtf8(context.requireParameter("name"), channel.getAddress());
                        return List.of();
                    }
                }
                """.formatted(API_PACKAGE, Channel.class.getName());
    }
    private GradleRunner runner(Path root, String... arguments) {
        List<String> args = new ArrayList<>(List.of(arguments));
        args.addAll(List.of("--offline", "--build-cache", "--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace"));
        GradleRunner runner = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withTestKitDir(new File(System.getProperty("asyncwright.testKitDir"))).withArguments(args);
        String version = System.getProperty("asyncwright.testGradleVersion", "");
        return version.isEmpty() ? runner.withGradleInstallation(new File(System.getProperty("asyncwright.testGradleHome")))
                : runner.withGradleVersion(version);
    }
    private BuildResult run(Path root, String... arguments) { return runner(root, arguments).build(); }
    private BuildResult fail(Path root, String... arguments) { return runner(root, arguments).buildAndFail(); }
    private static TaskOutcome outcome(BuildResult result, String taskPath) {
        BuildTask task = result.task(taskPath);
        assertNotNull(task, () -> "Expected task " + taskPath + " in build result:\n" + result.getOutput());
        return task.getOutcome();
    }
    private static List<Path> pluginClasspath() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = AsyncwrightPluginTest.class.getClassLoader().getResourceAsStream("plugin-under-test-metadata.properties")) {
            properties.load(Objects.requireNonNull(input));
        }
        return Arrays.stream(properties.getProperty("implementation-classpath").split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(Path::of).toList();
    }
    private static Path location(Class<?> type) throws Exception { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()); }
    private static String files(List<Path> paths) { return String.join(",", paths.stream().map(path -> "'" + slash(path).replace("'", "\\'") + "'").toList()); }
    private static String slash(Path path) { return path.toAbsolutePath().toString().replace('\\', '/'); }
    private static void write(Path root, String file, String text) throws IOException {
        Path path = root.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }
    private static void append(Path root, String file, String text) throws IOException { Files.writeString(root.resolve(file), text, StandardOpenOption.APPEND); }
    private static String read(Path root, String file) throws IOException { return Files.readString(root.resolve(file)); }
    private static void copySources(Path source, Path destination) throws IOException {
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = source.relativize(file);
                if (relative.toString().contains("build" + File.separator) || relative.startsWith(".gradle")) continue;
                Path target = destination.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
    }
}
