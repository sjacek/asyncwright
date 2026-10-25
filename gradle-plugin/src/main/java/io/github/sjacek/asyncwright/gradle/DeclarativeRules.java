package io.github.sjacek.asyncwright.gradle;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.sjacek.asyncwright.api.AsyncApiRule;
import io.github.sjacek.asyncwright.api.BuiltinRuleId;
import io.github.sjacek.asyncwright.api.Diagnostic;
import io.github.sjacek.asyncwright.api.ExtensionContext;
import io.github.sjacek.asyncwright.api.JsonPointers;
import io.github.sjacek.asyncwright.api.ResolvedReference;
import io.github.sjacek.asyncwright.api.Severity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static io.github.sjacek.asyncwright.api.AsyncApiFields.ADDRESS;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.CHANNELS;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.DEFAULT_CONTENT_TYPE;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.PROTOCOL;
import static io.github.sjacek.asyncwright.api.AsyncApiFields.SERVERS;

/**
 * Built-in DSL policy executed after parser validation in the isolated extension context.
 * Protocol alternatives are project policy, independent of protocol-specific binding semantics.
 * Findings point at resolved definitions; the executor deduplicates repeated uses of a definition.
 */
public final class DeclarativeRules implements AsyncApiRule {
    /** Read exactly one JSON value; accepting a valid prefix would hide malformed extension configuration. */
    private static final ObjectReader PROTOCOL_LIST = new ObjectMapper().reader()
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /**
     * Creates the built-in policy callback loaded by the worker extension executor.
     */
    public DeclarativeRules() { }

    enum Parameter {
        CHANNEL_ADDRESS("channelAddress"), CHANNEL_ADDRESS_FLAGS("channelAddressFlags"),
        CONTENT_TYPE("contentType"), PROTOCOL("protocol"), PROTOCOLS("protocols");

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
        List<String> protocols = protocols(context);
        if (!protocols.isEmpty()) {
            context.document().source().path(SERVERS).fieldNames().forEachRemaining(name -> {
                ResolvedReference server = context.document().references().resolve(JsonPointers.append("/" + SERVERS, name)).orElseThrow();
                if (!protocols.contains(server.node().path(PROTOCOL).asText()))
                    diagnostics.add(context.diagnostic(BuiltinRuleId.PROJECT_PROTOCOL, Severity.ERROR,
                            "Expected server protocol in " + protocols, JsonPointers.append(server.pointer(), PROTOCOL)));
            });
        }
        return diagnostics;
    }

    /**
     * JSON encoding retains exact names across the text-only extension parameter boundary,
     * including punctuation. An explicitly supplied list, even [], wins over the legacy scalar
     * parameter used by existing ExtensionSpec callers. Invalid configuration is an execution
     * failure rather than a finding against the user's contract.
     */
    private static List<String> protocols(ExtensionContext context) {
        String encoded = context.parameters().get(Parameter.PROTOCOLS.key());
        if (encoded == null) {
            String legacy = Parameter.PROTOCOL.read(context);
            return legacy.isEmpty() ? List.of() : List.of(legacy);
        }
        try {
            var values = PROTOCOL_LIST.readTree(encoded);
            if (values == null || !values.isArray())
                throw new IllegalArgumentException("Expected a JSON array of protocol names");
            List<String> protocols = new ArrayList<>();
            for (var value : values) {
                if (!value.isTextual() || value.textValue().isBlank())
                    throw new IllegalArgumentException("Allowed protocols must be nonblank strings");
                if (!protocols.contains(value.textValue())) protocols.add(value.textValue());
            }
            return List.copyOf(protocols);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid protocol list", exception);
        }
    }
}
