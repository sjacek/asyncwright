package sample;

import io.github.sjacek.asyncwright.api.AsyncApiRule;
import io.github.sjacek.asyncwright.api.Diagnostic;
import io.github.sjacek.asyncwright.api.JsonPointers;
import io.github.sjacek.asyncwright.api.Severity;
import io.github.sjacek.asyncwright.api.ExtensionContext;
import java.util.ArrayList;
import java.util.List;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.*;

/** Checks a required prefix at resolved channel definitions and reports their reference usages. */
public final class ChannelAddressRule implements AsyncApiRule {
    /** Public no-argument constructor required by the extension loader. */
    public ChannelAddressRule() { }

    /** Required text parameter holding the channel address prefix. */
    public static final String PREFIX_PARAMETER = "prefix";
    /** Stable project rule identifier emitted in reports. */
    public static final String RULE_ID = "sample.channel-prefix";
    @Override
    public List<Diagnostic> validate(ExtensionContext context) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        String prefix = context.requireParameter(PREFIX_PARAMETER);
        context.document().source().path(CHANNELS).fieldNames().forEachRemaining(name -> {
            String pointer = JsonPointers.append("/" + CHANNELS, name);
            var channel = context.document().references().resolve(pointer).orElseThrow();
            if (!channel.node().path(ADDRESS).asText().startsWith(prefix))
                diagnostics.add(context.diagnostic(RULE_ID, Severity.ERROR,
                        "Expected channel prefix " + prefix, JsonPointers.append(channel.pointer(), ADDRESS)));
        });
        return diagnostics;
    }
}
