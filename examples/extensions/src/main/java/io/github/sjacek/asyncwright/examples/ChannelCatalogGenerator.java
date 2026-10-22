package io.github.sjacek.asyncwright.examples;

import com.asyncapi.v3._0_0.model.channel.Channel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import io.github.sjacek.asyncwright.api.*;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Generates a deterministic UTF-8 catalog while preserving references in the input model. */
public final class ChannelCatalogGenerator implements AsyncApiGenerator {
    public static final String FILE_PARAMETER = "file";
    public static final String DEFAULT_OUTPUT_FILE = "channels.json";
    @Override
    public List<Diagnostic> generate(GeneratorContext context) throws Exception {
        Map<String, String> catalog = new TreeMap<>();
        var channels = context.document().model().getChannels();
        if (channels != null) for (String name : channels.keySet()) {
            String pointer = JsonPointers.append("/" + AsyncApiFields.CHANNELS, name);
            Channel channel = context.document().references().resolve(pointer, Channel.class).orElseThrow();
            catalog.put(name, channel.getAddress());
        }
        var printer = new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n"));
        String json = new ObjectMapper().writer(printer).writeValueAsString(catalog) + "\n";
        context.output().writeUtf8(context.parameters().getOrDefault(FILE_PARAMETER, DEFAULT_OUTPUT_FILE), json);
        return List.of();
    }
}
