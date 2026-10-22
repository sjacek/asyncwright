package io.github.sjacek.asyncwright.examples;

import com.asyncapi.v3._0_0.model.channel.Channel;
import io.github.sjacek.asyncwright.api.*;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Example project rule using typed channel targets and definition/use diagnostics. */
public final class ChannelPrefixRule implements AsyncApiRule {
    @Override
    public List<Diagnostic> validate(ExtensionContext context) throws Exception {
        String prefix = context.requireParameter("prefix");
        List<Diagnostic> diagnostics = new ArrayList<>();
        var channels = context.document().model().getChannels();
        if (channels == null) return diagnostics;
        for (String name : new TreeSet<>(channels.keySet())) {
            String pointer = "/channels/" + name.replace("~", "~0").replace("/", "~1");
            var target = context.document().references().resolve(pointer).orElseThrow();
            Channel channel = context.document().references().resolve(pointer, Channel.class).orElseThrow();
            if (channel.getAddress() != null && !channel.getAddress().startsWith(prefix))
                diagnostics.add(context.diagnostic("example.channel-prefix", Severity.ERROR,
                        "Channel address must start with " + prefix, target.pointer() + "/address"));
        }
        return diagnostics;
    }
}
