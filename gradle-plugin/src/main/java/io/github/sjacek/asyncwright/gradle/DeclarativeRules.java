package io.github.sjacek.asyncwright.gradle;

import io.github.sjacek.asyncwright.api.*;
import java.util.*;
import java.util.regex.Pattern;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.*;

/** Built-in DSL rules run in the same isolated context as project rules. */
public final class DeclarativeRules implements AsyncApiRule {
    enum Parameter {
        CHANNEL_ADDRESS("channelAddress"), CHANNEL_ADDRESS_FLAGS("channelAddressFlags"),
        CONTENT_TYPE("contentType"), PROTOCOL("protocol");

        private final String key;
        Parameter(String key) { this.key = key; }
        String key() { return key; }
        String read(ExtensionContext context) { return context.requireParameter(key); }
    }

    @Override public List<Diagnostic> validate(ExtensionContext context) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        String contentType = Parameter.CONTENT_TYPE.read(context);
        if (!contentType.isEmpty() && !contentType.equals(context.document().source().path(DEFAULT_CONTENT_TYPE).asText()))
            diagnostics.add(context.diagnostic(BuiltinRuleId.PROJECT_DEFAULT_CONTENT_TYPE, Severity.ERROR,
                    "Expected defaultContentType " + contentType, "/" + DEFAULT_CONTENT_TYPE));
        String expression = Parameter.CHANNEL_ADDRESS.read(context);
        if (!expression.isEmpty()) {
            // Flags originate from Pattern.flags() and cross the worker boundary as a string.
            //noinspection MagicConstant
            Pattern pattern = Pattern.compile(expression, Integer.parseInt(Parameter.CHANNEL_ADDRESS_FLAGS.read(context)));
            context.document().source().path(CHANNELS).fieldNames().forEachRemaining(name -> {
                ResolvedReference channel = context.document().references().resolve(JsonPointers.append("/" + CHANNELS, name)).orElseThrow();
                var address = channel.node().path(ADDRESS);
                if (!address.isTextual() || !pattern.matcher(address.textValue()).matches())
                    diagnostics.add(context.diagnostic(BuiltinRuleId.PROJECT_CHANNEL_ADDRESS, Severity.ERROR,
                            "Expected channel address matching " + expression, JsonPointers.append(channel.pointer(), ADDRESS)));
            });
        }
        String protocol = Parameter.PROTOCOL.read(context);
        if (!protocol.isEmpty()) {
            context.document().source().path(SERVERS).fieldNames().forEachRemaining(name -> {
                ResolvedReference server = context.document().references().resolve(JsonPointers.append("/" + SERVERS, name)).orElseThrow();
                if (!protocol.equals(server.node().path(PROTOCOL).asText()))
                    diagnostics.add(context.diagnostic(BuiltinRuleId.PROJECT_PROTOCOL, Severity.ERROR,
                            "Expected server protocol " + protocol, JsonPointers.append(server.pointer(), PROTOCOL)));
            });
        }
        return diagnostics;
    }
}
