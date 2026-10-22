package io.github.sjacek.asyncwright.examples;

import io.github.sjacek.asyncwright.api.ExtensionSpec;
import io.github.sjacek.asyncwright.parser.AsyncApiParser;
import io.github.sjacek.asyncwright.parser.ExtensionExecutor;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Ordinary Java entry point; no Gradle API is used by these extensions or their host. */
public final class ExampleMain {
    private ExampleMain() { }

    /**
     * Runs validation and catalog generation using the same API as isolated Gradle workers.
     * @param args specification file and output directory
     * @throws Exception if arguments, input validation or extension execution fail
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: " + ExampleMain.class.getSimpleName() + " <specification> <output-directory>");
        Path input = Path.of(args[0]).toAbsolutePath().normalize();
        var parsed = new AsyncApiParser().parse(input.getParent(), input.getFileName());
        var result = new ExtensionExecutor(ExampleMain.class.getClassLoader()).generate(parsed,
                List.of(new ExtensionSpec(ChannelPrefixRule.class.getName(), Map.of(ChannelPrefixRule.PREFIX_PARAMETER, "events."))),
                List.of(new ExtensionSpec(ChannelCatalogGenerator.class.getName(), Map.of(ChannelCatalogGenerator.FILE_PARAMETER, "catalog/channels.json"))),
                Path.of(args[1]));
        if (!result.successful()) throw new IllegalStateException(result.diagnosticsJson());
        System.out.println("Generated: " + String.join(", ", result.generatedFiles()));
    }
}
