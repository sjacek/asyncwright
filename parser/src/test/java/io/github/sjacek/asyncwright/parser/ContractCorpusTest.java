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

/**
 * Opt-in regression against the current nine application contracts, kept outside this repository.
 * Exact generated-file hashes belong to the consumer's baseline test. Reference and channel
 * counts here are observations, not a frozen copy of an older application's contract layout.
 */
@Tag("external-corpus")
class ContractCorpusTest {
    @Test
    void validatesTypedModelsAndResolvesAllLocalReferences() throws Exception {
        Path corpus = Path.of(System.getProperty("asyncwright.contractsDir"));
        AsyncApiParser parser = new AsyncApiParser();
        List<Map<String, Object>> reports = new ArrayList<>();
        int totalReferences = 0, totalBindings = 0, expectedBindings = 0;
        for (String name : List.of("assistant-live-events.yaml", "document-import-job-events.yaml", "forum-live-events.yaml",
                "office-invalidation-events.yaml", "ops-application-events.yaml", "portal-notification-live-events.yaml",
                "sms-gateway-events.yaml", "sms-gateway-policies.yaml", "sms-gateway-test-commands.yaml")) {
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
            expectedBindings += document.source().path("channels").size();
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
        assertEquals(9, reports.size());
        assertTrue(totalReferences > 0, "The corpus must exercise reference resolution");
        assertEquals(expectedBindings, totalBindings, "Every source channel must retain its typed AMQP binding");
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
