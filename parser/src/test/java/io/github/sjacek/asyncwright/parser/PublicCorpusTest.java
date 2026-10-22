package io.github.sjacek.asyncwright.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sjacek.asyncwright.api.Diagnostic;
import io.github.sjacek.asyncwright.api.Severity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Public, offline baseline checks. Known F1 gaps are explicit in the manifest, not conformance passes. */
@Tag("public-corpus")
class PublicCorpusTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] MANIFEST_BYTES = resource("manifest.json");
    private static final JsonNode MANIFEST = manifest();
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();

    private static byte[] resource(String file) {
        try (var input = Objects.requireNonNull(PublicCorpusTest.class.getResourceAsStream("/conformance/" + file), file)) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read corpus resource " + file, exception);
        }
    }

    private static JsonNode manifest() {
        try { return MAPPER.readTree(MANIFEST_BYTES); }
        catch (IOException exception) { throw new IllegalStateException("Cannot read corpus manifest", exception); }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void manifestAccountsForEveryFixtureAndReviewsEveryDifference() throws Exception {
        assertEquals(1, MANIFEST.path("formatVersion").asInt());
        Set<String> ids = new HashSet<>(), files = new HashSet<>();
        files.add("manifest.json");
        Set<String> classifications = Set.of("agreement", "unsupported", "model-limit", "policy-difference",
                "reference-parser-difference", "implementation-gap");
        for (JsonNode entry : MANIFEST.path("cases")) {
            String id = entry.path("id").asText();
            assertFalse(id.isBlank());
            assertTrue(ids.add(id), "Duplicate case: " + id);
            assertTrue(files.add(entry.path("file").asText()), "Duplicate fixture: " + id);
            assertTrue(classifications.contains(entry.path("classification").asText()), id);
            assertFalse(entry.path("reason").asText().isBlank(), id);
            assertTrue(entry.path("asyncwright").path("successful").isBoolean(), id);
            assertTrue(entry.path("javascript").path("successful").isBoolean(), id);
            assertTrue(entry.path("asyncwright").path("errorRules").isArray(), id);
            assertTrue(entry.path("javascript").path("errorCodes").isArray(), id);
            assertEquals(entry.path("classification").asText().equals("agreement"),
                    entry.path("asyncwright").path("successful").asBoolean()
                            == entry.path("javascript").path("successful").asBoolean(), id);
        }
        for (JsonNode asset : MANIFEST.path("assets")) {
            String file = asset.path("file").asText();
            assertTrue(files.add(file), "Duplicate asset: " + file);
            assertEquals(asset.path("sha256").asText(), sha256(resource(file)), file);
        }
        Path directory = Path.of(Objects.requireNonNull(getClass().getResource("/conformance")).toURI());
        try (var paths = Files.walk(directory)) {
            Set<String> actual = new HashSet<>();
            paths.filter(Files::isRegularFile).forEach(file -> actual.add(directory.relativize(file).toString().replace('\\', '/')));
            assertEquals(files, actual, "Every corpus file must have a manifest entry");
        }
    }

    @TestFactory
    Stream<DynamicTest> preservesTheReviewedPublicCorpusBaseline() {
        AsyncApiParser parser = new AsyncApiParser();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode entry : MANIFEST.path("cases")) {
            tests.add(DynamicTest.dynamicTest(entry.path("id").asText(), () -> {
                String file = entry.path("file").asText();
                byte[] bytes = resource(file);
                String checksum = sha256(bytes);
                assertEquals(entry.path("sha256").asText(), checksum, file);
                if (entry.has("upstreamGitBlob")) {
                    MessageDigest blob = MessageDigest.getInstance("SHA-1");
                    blob.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
                    assertEquals(entry.path("upstreamGitBlob").asText(), HexFormat.of().formatHex(blob.digest(bytes)), file);
                }
                ParseResult result = parser.parse(file, new String(bytes, StandardCharsets.UTF_8));
                var errors = result.diagnostics().stream().filter(d -> d.severity() == Severity.ERROR).toList();
                List<String> rules = errors.stream().map(Diagnostic::ruleId).distinct().sorted().toList();
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("id", entry.path("id").asText());
                report.put("file", file);
                report.put("sha256", checksum);
                report.put("successful", result.successful());
                report.put("documentPresent", result.document().isPresent());
                report.put("errorRules", rules);
                report.put("diagnostics", result.diagnostics());
                RESULTS.add(report);
                String context = entry.path("id").asText() + ": " + result.diagnosticsJson();
                assertEquals(entry.path("asyncwright").path("successful").asBoolean(), result.successful(), context);
                assertEquals(result.successful(), result.document().isPresent(), context);
                assertEquals(entry.path("asyncwright").path("errorRules"), MAPPER.valueToTree(rules), context);
                if (entry.path("asyncwright").has("firstErrorPointer"))
                    assertEquals(entry.path("asyncwright").path("firstErrorPointer").asText(), errors.get(0).pointer(), context);
                for (var diagnostic : result.diagnostics()) {
                    assertEquals(file, diagnostic.file());
                    assertTrue(diagnostic.line() >= 1 && diagnostic.column() >= 1, context);
                    assertTrue(diagnostic.pointer().isEmpty() || diagnostic.pointer().startsWith("/"), context);
                }
            }));
        }
        return tests.stream();
    }

    @AfterAll
    static void writeReport() throws Exception {
        String destination = System.getProperty("asyncwright.publicCorpusReport");
        if (destination == null) return;
        Path file = Path.of(destination);
        Files.createDirectories(file.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), Map.of(
                "formatVersion", 1, "manifestSha256", sha256(MANIFEST_BYTES), "results", RESULTS));
    }
}
