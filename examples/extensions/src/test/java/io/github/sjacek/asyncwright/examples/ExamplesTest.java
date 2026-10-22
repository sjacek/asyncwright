package io.github.sjacek.asyncwright.examples;

import io.github.sjacek.asyncwright.api.ExtensionSpec;
import io.github.sjacek.asyncwright.parser.AsyncApiParser;
import io.github.sjacek.asyncwright.parser.ExtensionExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ExamplesTest {
    @TempDir Path directory;

    private static final String INPUT = """
            asyncapi: 3.0.0
            info: {title: Example, version: 1.0.0}
            channels:
              created: {address: events.demo.created}
              updated: {$ref: '#/components/channels/updated'}
            components:
              channels:
                updated: {address: events.demo.updated}
            """;

    @Test
    void exampleGeneratesAStableCatalogAndRetainsReferences() throws Exception {
        var parsed = new AsyncApiParser().parse("events.yaml", INPUT);
        var result = new ExtensionExecutor().generate(parsed,
                List.of(new ExtensionSpec(ChannelPrefixRule.class.getName(), Map.of("prefix", "events."))),
                List.of(new ExtensionSpec(ChannelCatalogGenerator.class.getName(), Map.of("file", "catalog/channels.json"))), directory);
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertEquals(List.of("catalog/channels.json"), result.generatedFiles());
        assertEquals("""
                {
                  "created" : "events.demo.created",
                  "updated" : "events.demo.updated"
                }
                """, Files.readString(directory.resolve("catalog/channels.json")));
        assertEquals("#/components/channels/updated", parsed.document().orElseThrow().source().at("/channels/updated/$ref").asText());
    }

    @Test
    void ruleReportsTheSharedDefinitionOnceWithAllUsesAndSkipsGeneration() throws Exception {
        String invalid = INPUT.replace("events.demo.updated", "wrong.updated")
                .replace("  updated: {$ref:", "  alias: {$ref: '#/components/channels/updated'}\n  updated: {$ref:");
        var result = new ExtensionExecutor().generate(new AsyncApiParser().parse("events.yaml", invalid),
                List.of(new ExtensionSpec(ChannelPrefixRule.class.getName(), Map.of("prefix", "events."))),
                List.of(new ExtensionSpec(ChannelCatalogGenerator.class.getName(), Map.of())), directory.resolve("output"));
        assertFalse(result.successful());
        assertEquals(1, result.diagnostics().size());
        var error = result.diagnostics().get(0);
        assertEquals("/components/channels/updated/address", error.pointer());
        assertEquals(2, error.related().size());
        assertFalse(Files.exists(directory.resolve("output")));
    }

    @Test
    void standaloneJavaMainRunsTheCompleteExample() throws Exception {
        Path input = directory.resolve("events.yaml");
        Files.writeString(input, INPUT);
        ExampleMain.main(new String[] { input.toString(), directory.resolve("output").toString() });
        assertTrue(Files.readString(directory.resolve("output/catalog/channels.json")).contains("events.demo.updated"));
    }
}
