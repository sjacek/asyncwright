package io.github.sjacek.asyncwright.gradle;

import io.github.sjacek.asyncwright.generator.java.JavaDtoGenerator;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the bundled backend inside the real isolated worker, with both Gradle DSLs. */
class JavaDtoWorkerTest {
    @TempDir
    Path directory;
    private static final String TASK = ":asyncApiGenerateEventsDto";
    private static final String INPUT = """
            {"asyncapi":"3.0.0","info":{"title":"Worker DTO","version":"1"},
             "components":{"messages":{"event":{"payload":{"$ref":"#/components/schemas/Event"}}},
              "schemas":{"Event":{"type":"object","additionalProperties":false,"required":["count"],
                "properties":{"count":{"type":"integer","format":"int64"}}}}}}
            """;

    @ParameterizedTest(name = "bundled Java DTO worker with {0} DSL")
    @ValueSource(strings = {"groovy", "kotlin"})
    void compilesConsumersCachesResultsAndRemovesStaleOutputOnErrors(String dsl) throws Exception {
        Files.writeString(directory.resolve("settings.gradle"),
                "rootProject.name = 'java-dto-consumer'\nbuildCache { local { directory = 'test-cache' } }\n");
        Files.writeString(directory.resolve("gradle.properties"),
                "org.gradle.jvmargs=-Xmx384m -Dfile.encoding=UTF-8\norg.gradle.workers.max=2\n");
        Files.writeString(directory.resolve(dsl.equals("kotlin") ? "build.gradle.kts" : "build.gradle"), script(dsl));
        Path contract = directory.resolve("events.json");
        Files.writeString(contract, INPUT);
        Path client = directory.resolve("src/main/java/client/Client.java");
        Files.createDirectories(client.getParent());
        Files.writeString(client, """
                package client;
                public final class Client {
                    public static long read() {
                        fixture.dto.Event event = new fixture.dto.Event();
                        event.setCount(3_000_000_000L);
                        return event.getCount();
                    }
                }
                """);
        Path generated = directory.resolve("build/generated/asyncapi/events/dto/fixture/dto/Event.java");
        BuildResult first = runner("compileJava").build();
        assertEquals(TaskOutcome.SUCCESS, outcome(first));
        assertTrue(Files.readString(generated).contains("private long count;"));
        assertTrue(Files.isRegularFile(directory.resolve("build/classes/java/main/client/Client.class")));
        BuildResult same = runner("compileJava").build();
        assertEquals(TaskOutcome.UP_TO_DATE, outcome(same));
        assertTrue(same.getOutput().contains("Reusing configuration cache"));
        // The cache lives outside build/, so clean removes outputs without discarding the cache entry.
        BuildResult cached = runner("clean", "compileJava").build();
        assertEquals(TaskOutcome.FROM_CACHE, outcome(cached));
        assertTrue(Files.isRegularFile(generated));

        // The document still parses; the generator's narrower type profile must reject this shape.
        Files.writeString(contract, INPUT.replace("\"type\":\"integer\"", "\"type\":[\"integer\",\"string\"]"));
        BuildResult invalid = runner("compileJava").buildAndFail();
        assertTrue(invalid.getOutput().contains("java-dto.unsupported"), invalid::getOutput);
        assertFalse(Files.exists(generated));
        assertNull(invalid.task(":compileJava"));
        Files.writeString(contract, INPUT);
        BuildResult restored = runner("compileJava").build();
        assertEquals(TaskOutcome.FROM_CACHE, outcome(restored));
        assertTrue(Files.isRegularFile(generated));
    }

    @Test
    void restoresRelocatedOutputsAndInvalidatesPackageParameters() throws Exception {
        Path first = directory.resolve("first");
        Path relocated = directory.resolve("relocated");
        Path cache = directory.resolve("shared-cache");
        String settings = "rootProject.name = 'java-dto-consumer'\n"
                + "buildCache { local { directory = uri('" + cache.toUri() + "') } }\n";
        for (Path project : List.of(first, relocated)) {
            Files.createDirectories(project);
            Files.writeString(project.resolve("settings.gradle"), settings);
            Files.writeString(project.resolve("gradle.properties"),
                    "org.gradle.jvmargs=-Xmx384m -Dfile.encoding=UTF-8\norg.gradle.workers.max=2\n");
            Files.writeString(project.resolve("build.gradle"), script("groovy"));
            Files.writeString(project.resolve("events.json"), INPUT);
        }
        String originalPath = "build/generated/asyncapi/events/dto/fixture/dto/Event.java";
        assertEquals(TaskOutcome.SUCCESS, outcome(runner(first, TASK).build()));
        byte[] expected = Files.readAllBytes(first.resolve(originalPath));
        assertEquals(TaskOutcome.FROM_CACHE, outcome(runner(relocated, TASK).build()));
        assertArrayEquals(expected, Files.readAllBytes(relocated.resolve(originalPath)));

        // A package parameter changes both API identity and path, while the contract stays identical.
        Files.writeString(relocated.resolve("build.gradle"),
                script("groovy").replace("'fixture.dto'", "'fixture.renamed'"));
        assertEquals(TaskOutcome.SUCCESS, outcome(runner(relocated, TASK).build()));
        Path renamed = relocated.resolve("build/generated/asyncapi/events/dto/fixture/renamed/Event.java");
        assertTrue(Files.readString(renamed).startsWith("package fixture.renamed;\n"));
        assertFalse(Files.exists(relocated.resolve(originalPath)), "The old package must not survive replacement");
        Files.writeString(relocated.resolve("build.gradle"), script("groovy"));
        assertEquals(TaskOutcome.FROM_CACHE, outcome(runner(relocated, TASK).build()));
        assertArrayEquals(expected, Files.readAllBytes(relocated.resolve(originalPath)));
        assertFalse(Files.exists(renamed), "Restoring a cached result must replace the whole task output");
    }

    private static String script(String dsl) {
        String generator = JavaDtoGenerator.class.getName();
        if (dsl.equals("kotlin")) return """
                import io.github.sjacek.asyncwright.gradle.AsyncApiGenerate
                import %s
                plugins { java; id("io.github.sjacek.asyncwright") }
                asyncwright {
                    specs {
                        register("events") {
                            file.set(layout.projectDirectory.file("events.json"))
                            generators {
                                register("dto") {
                                    className.set(JavaDtoGenerator::class.java.name)
                                    parameters.put(JavaDtoGenerator.PACKAGE_NAME, "fixture.dto")
                                }
                            }
                        }
                    }
                }
                val dto = tasks.named<AsyncApiGenerate>("asyncApiGenerateEventsDto")
                sourceSets.main { java.srcDir(dto.flatMap { it.outputDirectory }) }
                tasks.named("compileJava") { dependsOn(dto) }
                """.formatted(generator);
        return """
                import %s
                plugins { id 'java'; id 'io.github.sjacek.asyncwright' }
                asyncwright {
                    specs {
                        events {
                            file = layout.projectDirectory.file('events.json')
                            generators {
                                dto {
                                    className = JavaDtoGenerator.class.name
                                    parameters.put(JavaDtoGenerator.PACKAGE_NAME, 'fixture.dto')
                                }
                            }
                        }
                    }
                }
                sourceSets.main.java.srcDir(tasks.named('asyncApiGenerateEventsDto').flatMap { it.outputDirectory })
                tasks.named('compileJava') { dependsOn tasks.named('asyncApiGenerateEventsDto') }
                """.formatted(generator);
    }

    private GradleRunner runner(String... tasks) {
        return runner(directory, tasks);
    }

    private GradleRunner runner(Path project, String... tasks) {
        List<String> args = new ArrayList<>(List.of(tasks));
        args.addAll(List.of("--offline", "--build-cache", "--configuration-cache",
                "--configuration-cache-problems=fail", "--stacktrace"));
        GradleRunner runner = GradleRunner.create().withProjectDir(project.toFile())
                .withPluginClasspath().withArguments(args)
                .withTestKitDir(new File(System.getProperty("asyncwright.testKitDir")));
        String version = System.getProperty("asyncwright.testGradleVersion", "");
        if (version.isEmpty()) runner.withGradleInstallation(new File(System.getProperty("asyncwright.testGradleHome")));
        else runner.withGradleVersion(version);
        return runner;
    }

    private static TaskOutcome outcome(BuildResult result) {
        return Objects.requireNonNull(result.task(TASK), result.getOutput()).getOutcome();
    }
}
