package sample;

import com.asyncapi.v3._0_0.model.channel.Channel;
import io.github.sjacek.asyncwright.api.AsyncApiGenerator;
import io.github.sjacek.asyncwright.api.Diagnostic;
import io.github.sjacek.asyncwright.api.GeneratorContext;
import io.github.sjacek.asyncwright.api.JsonPointers;
import java.util.ArrayList;
import java.util.List;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.CHANNELS;

/** Demonstrates typed resolution and deterministic output using the published extension API. */
public final class ChannelCatalogGenerator implements AsyncApiGenerator {
    /** Public no-argument constructor required by the extension loader. */
    public ChannelCatalogGenerator() { }

    @Override public List<Diagnostic> generate(GeneratorContext context) throws Exception {
        List<String> addresses = new ArrayList<>();
        for (var entry : context.document().source().path(CHANNELS).properties()) {
            Channel channel = context.document().references()
                    .resolve(JsonPointers.append("/" + CHANNELS, entry.getKey()), Channel.class).orElseThrow();
            addresses.add(channel.getAddress());
        }
        addresses.sort(String::compareTo);
        context.output().writeUtf8("channels.txt", String.join("\n", addresses) + "\n");
        return List.of();
    }
}
