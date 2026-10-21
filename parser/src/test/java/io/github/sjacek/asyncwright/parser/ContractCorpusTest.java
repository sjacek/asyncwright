package io.github.sjacek.asyncwright.parser;

import com.asyncapi.bindings.amqp.AMQPChannelBinding;
import com.asyncapi.v3._0_0.model.channel.Channel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in corpus check; source documents remain outside this repository. */
@Tag("external-corpus")
class ContractCorpusTest {
    @Test
    void validatesTypedModelsAndResolvesAllLocalReferences() throws Exception {
        Path corpus = Path.of(System.getProperty("asyncwright.contractsDir"));
        AsyncApiParser parser = new AsyncApiParser();
        List<Map<String, Object>> reports = new ArrayList<>();
        int totalReferences = 0, totalBindings = 0;
        for (String name : List.of("document-import-job-events.yaml", "forum-live-events.yaml",
                "office-invalidation-events.yaml", "ops-application-events.yaml", "portal-notification-live-events.yaml")) {
            var result = parser.parse(corpus, Path.of(name));
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("file", name);
            report.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(corpus.resolve(name)))));
            report.put("diagnostics", result.diagnostics());
            report.put("successful", result.successful());
            reports.add(report);
            if (!result.successful()) continue;
            var document = result.document().orElseThrow();
            int references = verifyReferences(document.source(), "", document);
            totalReferences += references;
            int bindings = 0;
            var channels = document.model().getChannels();
            assertNotNull(channels, name + ": expected channels in the typed model");
            for (Object value : channels.values()) {
                Channel channel = assertInstanceOf(Channel.class, value);
                var channelBindings = channel.getBindings();
                assertNotNull(channelBindings, name + ": expected channel bindings in the typed model");
                assertInstanceOf(AMQPChannelBinding.class, channelBindings.get("amqp"));
                bindings++;
            }
            totalBindings += bindings;
            report.put("references", references);
            report.put("typedAmqpBindings", bindings);
        }
        Path report = Path.of(System.getProperty("asyncwright.corpusReport"));
        Files.createDirectories(report.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(report.toFile(), reports);
        assertTrue(reports.stream().allMatch(r -> Boolean.TRUE.equals(r.get("successful"))), reports::toString);
        assertEquals(91, totalReferences);
        assertEquals(13, totalBindings);
    }

    private int verifyReferences(JsonNode node, String pointer, AsyncApiDocument document) {
        int count = 0;
        if (node.isObject()) {
            if (node.has("$ref")) {
                assertTrue(document.references().resolve(pointer).isPresent(), pointer);
                assertEquals(node.get("$ref"), document.source().at(pointer).get("$ref"));
                count++;
            }
            for (var field : node.properties())
                count += verifyReferences(field.getValue(), pointer + "/" + SourceDocument.escape(field.getKey()), document);
        } else if (node.isArray()) for (int i = 0; i < node.size(); i++)
            count += verifyReferences(node.get(i), pointer + "/" + i, document);
        return count;
    }
}
