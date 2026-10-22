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

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: ExampleMain <specification> <output-directory>");
        Path input = Path.of(args[0]).toAbsolutePath().normalize();
        var parsed = new AsyncApiParser().parse(input.getParent(), input.getFileName());
        var result = new ExtensionExecutor(ExampleMain.class.getClassLoader()).generate(parsed,
                List.of(new ExtensionSpec(ChannelPrefixRule.class.getName(), Map.of("prefix", "events."))),
                List.of(new ExtensionSpec(ChannelCatalogGenerator.class.getName(), Map.of("file", "catalog/channels.json"))),
                Path.of(args[1]));
        if (!result.successful()) throw new IllegalStateException(result.diagnosticsJson());
        System.out.println("Generated: " + String.join(", ", result.generatedFiles()));
    }
}
