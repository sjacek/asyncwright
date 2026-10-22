package io.github.sjacek.asyncwright.gradle;

import com.sun.net.httpserver.HttpServer;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.*;

class PublishedConsumerTest {
    @TempDir Path temporary;

    @Test void resolvesMarkerPluginAndModelOverHttpAndRunsProjectExtensions() throws Exception {
        Path plugins = Path.of(System.getProperty("asyncwright.stagingRepository"));
        Path model = Path.of(System.getProperty("asyncwright.modelRepository")).toAbsolutePath();
        String modelVersion = System.getProperty("asyncwright.modelVersion");
        assertTrue(Files.isRegularFile(model.resolve("io/github/sjacek/jasyncapi/asyncapi/" + modelVersion
                + "/asyncapi-" + modelVersion + ".pom")), "Prepublication tests require the staged model parent POM");
        Set<String> requests = ConcurrentHashMap.newKeySet();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serve(server, "/plugins/", plugins, requests);
        serve(server, "/model/", model, requests);
        server.start();
        try {
            Path root = temporary.resolve("consumer");
            copyExample(root);
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            // This cold home has never received withPluginClasspath(), Maven Local or an included build.
            File home = new File(System.getProperty("asyncwright.testKitDir"), "published-consumer-" + UUID.randomUUID());
            GradleRunner runner = GradleRunner.create().withProjectDir(root.toFile()).withTestKitDir(home);
            String version = System.getProperty("asyncwright.testGradleVersion", "");
            if (version.isEmpty()) runner.withGradleInstallation(new File(System.getProperty("asyncwright.testGradleHome")));
            else runner.withGradleVersion(version);
            List<String> args = new ArrayList<>(List.of("check", "asyncApiGenerateEventsCatalog",
                    "-PasyncwrightVersion=" + System.getProperty("asyncwright.version"),
                    "-PasyncwrightPluginRepositoryUrl=" + origin + "/plugins/",
                    "-PasyncwrightModelRepositoryUrl=" + origin + "/model/",
                    "--configuration-cache", "--configuration-cache-problems=fail", "--continue", "--stacktrace"));
            BuildResult result = runner.withArguments(args).build();
            var validation = result.task(":asyncApiValidateEvents");
            assertNotNull(validation, result::getOutput);
            assertEquals(SUCCESS, validation.getOutcome());
            assertEquals("events.created\n", Files.readString(root.resolve("build/generated/asyncapi/events/catalog/channels.txt")));
            assertFalse(result.getOutput().contains("Unknown keyword example"), result::getOutput);
            assertTrue(requests.stream().anyMatch(p -> p.contains("io.github.sjacek.asyncwright.gradle.plugin") && p.endsWith(".pom")), requests::toString);
            assertTrue(requests.stream().anyMatch(p -> p.contains("asyncwright-gradle-plugin") && p.endsWith(".jar")), requests::toString);
            assertTrue(requests.stream().anyMatch(p -> p.contains("/asyncapi/") && p.endsWith(".pom")), requests::toString);
            assertTrue(requests.stream().anyMatch(p -> p.contains("/asyncapi-core/") && p.endsWith(".jar")), requests::toString);
            assertFalse(requests.stream().anyMatch(p -> p.contains("/parser/")), requests::toString);

            Path contract = root.resolve("contracts/events.yaml");
            Files.writeString(contract, Files.readString(contract).replace("events.created", "invalid.created"));
            BuildResult invalid = runner.withArguments(args).buildAndFail();
            assertTrue(invalid.getOutput().contains("sample.channel-prefix"), invalid::getOutput);
            assertTrue(invalid.getOutput().contains("Reusing configuration cache"), invalid::getOutput);
            assertFalse(Files.exists(root.resolve("build/generated/asyncapi/events/catalog")));
        } finally {
            server.stop(0);
        }
    }

    @Test void publicationIncludesSourcesJavadocAndBothLicenses() throws Exception {
        Path repository = Path.of(System.getProperty("asyncwright.stagingRepository"));
        String version = System.getProperty("asyncwright.version");
        Path directory = repository.resolve("io/github/sjacek/asyncwright/asyncwright-gradle-plugin").resolve(version);
        // Gradle timestamps SNAPSHOT files; select actual classified artifacts rather than a presumed filename.
        for (String classifier : List.of("-sources.jar", "-javadoc.jar")) {
            try (var paths = Files.list(directory)) {
                Path artifact = paths.filter(path -> path.getFileName().toString().endsWith(classifier)).findFirst().orElseThrow();
                try (JarFile jar = new JarFile(artifact.toFile())) {
                    assertNotNull(jar.getEntry("META-INF/LICENSE"));
                    String apiEntry = classifier.equals("-sources.jar")
                            ? "io/github/sjacek/asyncwright/api/AsyncApiRule.java"
                            : "io/github/sjacek/asyncwright/api/AsyncApiRule.html";
                    assertNotNull(jar.getEntry(apiEntry), apiEntry);
                }
            }
        }
        try (JarFile jar = new JarFile(System.getProperty("asyncwright.pluginJar"))) {
            assertNotNull(jar.getEntry("META-INF/LICENSE"));
            assertNotNull(jar.getEntry("META-INF/licenses/spec-json-schemas/NOTICE"));
        }
    }

    private static void serve(HttpServer server, String prefix, Path root, Set<String> requests) {
        Path directory = root.toAbsolutePath().normalize();
        server.createContext(prefix, exchange -> {
            try (exchange) {
                String request = exchange.getRequestURI().getPath();
                Path file = directory.resolve(request.substring(prefix.length())).normalize();
                if (!file.startsWith(directory) || !Files.isRegularFile(file)) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                requests.add(request);
                if (exchange.getRequestMethod().equals("HEAD")) {
                    exchange.getResponseHeaders().set("Content-Length", Long.toString(Files.size(file)));
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(200, Files.size(file));
                    Files.copy(file, exchange.getResponseBody());
                }
            }
        });
    }

    private static void copyExample(Path target) throws Exception {
        Path example = Path.of(System.getProperty("asyncwright.projectDirectory"), "examples/published-consumer");
        try (var paths = Files.walk(example)) {
            for (Path source : paths.filter(Files::isRegularFile).toList()) {
                Path relative = example.relativize(source);
                if (relative.startsWith(".gradle") || relative.startsWith("build") || relative.startsWith("extensions/build")) continue;
                Path destination = target.resolve(relative);
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination);
            }
        }
    }
}
