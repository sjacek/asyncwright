package io.github.sjacek.asyncwright.parser;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.sjacek.asyncwright.api.AsyncApi30;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Loads production classes and the schema from the packaged parser, rather than test directories.
 * A sibling loader closes the same cached JAR exactly when schema reading starts. This makes
 * the CI failure deterministic without GC, timing assumptions or changes to global URL defaults.
 * <p>The sibling uses ordinary cached resource access; the production reader chooses its own
 * connection policy. External libraries are shared with the test host, while every isolated
 * parser has fresh Asyncwright classes and static schema initialization.
 */
class SchemaClassLoaderLifecycleTest {
    @TempDir Path directory;
    // ClassLoader resource names omit the leading slash accepted by Class.getResource.
    private static final String RESOURCE = AsyncApi30.SCHEMA_RESOURCE.substring(1);
    // Isolate the API and parser together so a parent-loaded helper cannot hide a cold initialization.
    private static final String OWN_PACKAGE = AsyncApiParser.class.getPackageName().replace(".parser", "") + ".";
    private static final String VALID = """
            {"asyncapi":"3.0.0","info":{"title":"Lifecycle","version":"1.0.0"}}
            """;

    /** Pins the adversarial control: an ordinary cached stream really is invalidated by its sibling. */
    @Test
    void cachedResourceControlFailsWhenAnotherLoaderClosesTheSharedJar() throws Exception {
        try (var owner = sibling(); var reader = new URLClassLoader(new URL[]{jar()}, null);
             var owned = resource(owner); var input = resource(reader)) {
            assertTrue(owned.read() >= 0);
            assertTrue(input.read() >= 0);
            // Deliberately close the loader while the reader stream is still inside its scope.
            owner.close();
            assertThrows(IOException.class, input::readAllBytes,
                    "The fixture must expose the shared cached-JAR lifetime before testing the fix");
        }
    }

    /**
     * Exercises SchemaCompatibility.schema itself while its stream is being invalidated externally.
     * Comparing the whole adapted tree also keeps resource ownership separate from schema corrections.
     */
    @Test
    void loadsTheWholeSchemaWhileAnotherClassLoaderClosesItsCachedResource() throws Exception {
        boolean defaultJarCache = URLConnection.getDefaultUseCaches("jar");
        try (var owner = sibling(); var owned = resource(owner);
             var reader = new IsolatedParserLoader(owner::close)) {
            assertTrue(owned.read() >= 0);
            assertEquals(SchemaCompatibility.schema(), schema(reader));
            reader.assertOwnedStream();
            assertEquals(SchemaCompatibility.schema(), schema(reader), "A later read must also get a fresh tree");
        }
        assertEquals(defaultJarCache, URLConnection.getDefaultUseCaches("jar"),
                "The loader must not change the daemon's global JAR cache policy");
    }

    /**
     * Repeated cold parser initialization models independent worker classloaders on two worker threads.
     * This is an in-process lifecycle regression; the contracts preview exercises actual Gradle workers.
     * The negative document proves that successful initialization still enables structural validation.
     */
    @Test
    void initializesAndValidatesInRepeatedConcurrentIsolatedClassLoaders() throws Exception {
        // Java 17 has no AutoCloseable executor; the finally block shuts down and awaits this pool.
        //noinspection resource
        var workers = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> invocations = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                // Each pair shares its own JAR; one pair must not invalidate another pair's fixture stream.
                URL isolatedJar = Files.copy(Path.of(jar().toURI()), directory.resolve("parser-" + index + ".jar"))
                        .toUri().toURL();
                invocations.add(() -> {
                    try (var owner = new URLClassLoader(new URL[]{isolatedJar}, null); var owned = resource(owner);
                         var reader = new IsolatedParserLoader(isolatedJar, owner::close)) {
                        assertTrue(owned.read() >= 0);
                        Class<?> parser = reader.loadClass(AsyncApiParser.class.getName());
                        assertSame(reader, parser.getClassLoader(), "Each invocation must initialize its own parser");
                        // Constructing this cold parser triggers the static schema read and the close hook.
                        Object instance = parser.getConstructor().newInstance();
                        var parse = parser.getMethod("parse", String.class, String.class);
                        Object valid = parse.invoke(instance, "contract.json", VALID);
                        assertEquals(Boolean.TRUE, valid.getClass().getMethod("successful").invoke(valid));
                        Object invalid = parse.invoke(instance, "contract.json", VALID.replace("\"Lifecycle\"", "42"));
                        assertEquals(Boolean.FALSE, invalid.getClass().getMethod("successful").invoke(invalid),
                                "An independent stream must not bypass structural validation");
                        reader.assertOwnedStream();
                        return true;
                    }
                });
            }
            // A bounded wait turns a stuck initialization into a failure, including cancelled futures.
            for (var result : workers.invokeAll(invocations, 60, TimeUnit.SECONDS))
                assertTrue(result.get(), "Every cold parser initialization must complete");
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "Fixture threads must not outlive the test");
        }
    }

    /** Uses the archive declared by the Gradle test task, including its real packaged schema bytes. */
    private static URL jar() throws IOException {
        return Path.of(Objects.requireNonNull(System.getProperty("asyncwright.parserJar"),
                "The test task must supply the packaged parser JAR")).toUri().toURL();
    }

    /** A parentless owner must obtain the resource from this JAR, never the test host's resource directory. */
    private static URLClassLoader sibling() throws IOException {
        return new URLClassLoader(new URL[]{jar()}, null);
    }

    /** Opens the intentional cached control and enrolls its JarFile in URLClassLoader.close cleanup. */
    private static InputStream resource(URLClassLoader loader) {
        return Objects.requireNonNull(loader.getResourceAsStream(RESOURCE), "Packaged schema missing");
    }

    /**
     * Invokes the production adaptation method from its isolated runtime package.
     * Reflection crosses the classloader boundary without introducing a public testing API;
     * unwrapping keeps the original I/O cause visible in the failing regression report.
     */
    private static JsonNode schema(ClassLoader loader) throws Exception {
        var method = loader.loadClass(SchemaCompatibility.class.getName()).getDeclaredMethod("schema");
        method.setAccessible(true);
        try {
            return (JsonNode) method.invoke(null);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) throw cause;
            throw exception;
        }
    }

    /** Checked-I/O hook for closing the real sibling loader during the first reader operation. */
    @FunctionalInterface
    private interface CloseSibling {
        /** Propagates close failures instead of turning an unsuccessful fixture setup into a passing test. */
        void close() throws IOException;
    }

    /**
     * Child-first only for Asyncwright's production namespace; external dependencies stay shared.
     * The resource connection delegates to the real JAR URL and forwards its cache setting.
     * Its stream closes the sibling before the first byte is read, then otherwise reads normally.
     */
    private static final class IsolatedParserLoader extends URLClassLoader {
        // The callback owns a different loader, while this loader stays usable for parsing.
        private final CloseSibling sibling;
        private final AtomicBoolean siblingClosed = new AtomicBoolean();
        // Observe cleanup of the first schema read independently of the sibling's cleanup.
        private final AtomicBoolean streamClosed = new AtomicBoolean();
        // One test thread uses each loader; only distinct loaders run concurrently.
        private final List<Boolean> cacheSettings = new ArrayList<>();

        /** Uses the shared packaged JAR for the single-reader adaptation test. */
        private IsolatedParserLoader(CloseSibling sibling) throws IOException {
            this(jar(), sibling);
        }

        /** Accepts a per-pair JAR copy when several independent lifecycles run concurrently. */
        private IsolatedParserLoader(URL jar, CloseSibling sibling) {
            super(new URL[]{jar}, SchemaClassLoaderLifecycleTest.class.getClassLoader());
            this.sibling = sibling;
        }

        /**
         * Defines Asyncwright classes locally so parser statics cannot be reused from the host.
         * Jackson and validator classes retain the parent loader's identity for tree comparisons.
         */
        @Override protected Class<?> loadClass(@NotNull String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(OWN_PACKAGE)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) type = findClass(name);
                if (resolve) resolveClass(type);
                return type;
            }
        }

        /**
         * Finds the schema in this JAR before wrapping its connection; parent resource directories
         * would bypass the JAR cache and make the broken production implementation appear correct.
         * All other resource lookups retain normal delegation.
         */
        @Override public URL getResource(String name) {
            if (!RESOURCE.equals(name)) return super.getResource(name);
            URL actual = Objects.requireNonNull(findResource(name), "Packaged schema missing");
            try {
                return new URL(null, actual.toExternalForm(), new URLStreamHandler() {
                    @Override protected URLConnection openConnection(URL url) {
                        return new URLConnection(url) {
                            // Open lazily so production code can first select its cache policy.
                            @Override public void connect() { }

                            @Override public InputStream getInputStream() throws IOException {
                                var connection = actual.openConnection();
                                // Forward the caller's setting to the real JAR connection, including the old default.
                                cacheSettings.add(getUseCaches());
                                connection.setUseCaches(getUseCaches());
                                return new FilterInputStream(connection.getInputStream()) {
                                    /** Close once at the first read, regardless of Jackson's bootstrap read shape. */
                                    private void closeSibling() throws IOException {
                                        if (siblingClosed.compareAndSet(false, true)) sibling.close();
                                    }
                                    /** Covers scalar reads without relying on a particular buffering strategy. */
                                    @Override public int read() throws IOException {
                                        closeSibling();
                                        return in.read();
                                    }
                                    /** Covers the bulk reads normally used while detecting and parsing JSON encoding. */
                                    @Override public int read(byte @NotNull [] buffer, int offset, int length) throws IOException {
                                        closeSibling();
                                        return in.read(buffer, offset, length);
                                    }
                                    /** Observe caller cleanup while preserving the delegate's close behavior and errors. */
                                    @Override public void close() throws IOException {
                                        streamClosed.set(true);
                                        super.close();
                                    }
                                };
                            }
                        };
                    }
                });
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot wrap the fixture resource URL", exception);
            }
        }

        /** Checks that the hook ran, caller cleanup occurred and schema connections avoided the shared cache. */
        private void assertOwnedStream() {
            assertTrue(siblingClosed.get(), "The sibling must have closed during schema reading");
            assertTrue(streamClosed.get(), "Schema loading must close its own stream");
            assertFalse(cacheSettings.isEmpty(), "Read the real packaged schema through its connection");
            assertTrue(cacheSettings.stream().noneMatch(Boolean::booleanValue),
                    "Only schema connections must opt out of the shared JAR cache");
        }
    }
}
