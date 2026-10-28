package io.github.sjacek.asyncwright.parser;

import io.github.sjacek.asyncwright.api.BuiltinRuleId;
import io.github.sjacek.asyncwright.api.Diagnostic;
import io.github.sjacek.asyncwright.api.ResolvedReference;
import io.github.sjacek.asyncwright.api.Severity;
import io.github.sjacek.asyncwright.api.SourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.github.sjacek.asyncwright.api.AsyncApiFields.*;

/**
 * Offline Kafka binding dependencies, evaluated against actual root operation usages.
 * Reusable operations/messages do not create usages on their own. All aliases, including
 * whole binding maps, go through the existing resolver; the source and model keep their refs.
 * Instances are per parse so related locations never leak between documents or workers.
 *
 * <p>There are two different dependency checks: vendor/URL is local to a server binding,
 * including an unused component; message metadata/URL depends on a concrete operation's
 * server selection. An unused message binding therefore creates no Registry requirement.
 * Structural validation owns field types and URL syntax; this rule checks presence and usage,
 * never the Registry's reachability, contents or credentials.</p>
 */
final class KafkaSchemaRegistryRule {
    private static final String KAFKA = "kafka";
    private static final Set<String> KAFKA_PROTOCOLS = Set.of(KAFKA, "kafka-secure");
    private static final String REGISTRY_URL = "schemaRegistryUrl";
    private static final String REGISTRY_VENDOR = "schemaRegistryVendor";
    // Stable precedence chooses one primary field when several fields require the same URL.
    private static final List<String> MESSAGE_METADATA = List.of(
            "schemaIdLocation", "schemaIdPayloadEncoding", "schemaLookupStrategy");

    private final SourceDocument source;
    private final DocumentIndex index;
    private final ReferenceResolver references;
    private final Map<FindingKey, Finding> findings = new LinkedHashMap<>();

    KafkaSchemaRegistryRule(SourceDocument source, DocumentIndex index, ReferenceResolver references) {
        this.source = source;
        this.index = index;
        this.references = references;
    }

    /**
     * Collects local and usage-dependent findings after structural/capability/reference validation.
     * Only root operation entries create usages, even when their bodies resolve to components.
     * Invalid references suppress the checks that require their targets; independent checks continue.
     * @return immutable diagnostics with accumulated related locations; the parser sorts the final result
     */
    List<Diagnostic> validate() {
        validateVendors();
        for (var operation : source.root.path(OPERATIONS).properties()) {
            String use = "/" + OPERATIONS + "/" + SourceDocument.escape(operation.getKey());
            references.resolve(use).ifPresent(target -> validateOperation(use, target));
        }
        return findings.values().stream().map(Finding::diagnostic).toList();
    }

    /** A vendor without a URL is invalid even in an unused server or binding-map component. */
    private void validateVendors() {
        for (var entry : index.kinds.entrySet()) {
            boolean server = entry.getValue() == DocumentIndex.Kind.SERVER;
            if (!server && entry.getValue() != DocumentIndex.Kind.SERVER_BINDINGS) continue;
            String pointer = entry.getKey();
            if (source.root.at(pointer).has(REF)) continue;
            var kafka = server ? kafkaBinding(pointer) : references.resolve(pointer + "/" + KAFKA);
            kafka.ifPresent(binding -> {
                if (binding.node().has(REGISTRY_VENDOR) && !binding.node().has(REGISTRY_URL)) {
                    String field = binding.pointer() + "/" + REGISTRY_VENDOR;
                    Finding finding = finding(field, "", Severity.ERROR,
                            "Kafka schemaRegistryVendor requires schemaRegistryUrl on the same server binding");
                    // Visiting a server and its map can reach the same definition. Keep one
                    // finding, but accumulate every concrete server and transitive map use.
                    if (server) finding.related.add(source.location(pointer));
                    finding.related.addAll(references.related(field));
                }
            });
        }
    }

    /** Keeps the root usage as related context while reading fields at the concrete definition. */
    private void validateOperation(String use, ResolvedReference operation) {
        Optional<ResolvedReference> channel = references.resolve(operation.pointer() + "/" + CHANNEL);
        channel.ifPresent(target -> validateContext(use, operation, target));
        references.resolve(operation.pointer() + "/" + REPLY).ifPresent(reply -> {
            // A missing reply channel means a dynamic address in this profile. An invalid
            // explicit reference is unknown: never silently fall back to the request channel.
            // An explicit reply channel is independent of an invalid request channel.
            Optional<ResolvedReference> replyChannel = reply.node().has(CHANNEL)
                    ? references.resolve(reply.pointer() + "/" + CHANNEL) : channel;
            replyChannel.ifPresent(target -> validateContext(use, reply, target));
        });
    }

    /**
     * Explicit messages select a subset; absent/empty lists select all messages of this channel.
     * The owner is either an operation or a reply. In the dynamic-reply fallback the request
     * channel supplies all messages, independently of the request operation's own selection.
     */
    private void validateContext(String operationUse, ResolvedReference owner, ResolvedReference channel) {
        List<ResolvedReference> messages = new ArrayList<>();
        if (!owner.node().path(MESSAGES).isEmpty()) {
            for (int i = 0; i < owner.node().path(MESSAGES).size(); i++)
                references.resolve(owner.pointer() + "/" + MESSAGES + "/" + i).ifPresent(messages::add);
        } else {
            for (var member : channel.node().path(MESSAGES).properties())
                references.resolve(channel.pointer() + "/" + MESSAGES + "/"
                        + SourceDocument.escape(member.getKey())).ifPresent(messages::add);
        }
        ServerContext servers = servers(channel);
        for (ResolvedReference message : messages) kafkaBinding(message.pointer()).ifPresent(binding -> {
            for (String metadata : MESSAGE_METADATA) if (binding.node().has(metadata)) {
                // All three fields impose the same dependency. Report the stable first one,
                // not three duplicate errors for one binding/server pair.
                validateMetadata(operationUse, channel.pointer(), binding.pointer() + "/" + metadata, servers);
                break;
            }
        });
    }

    /**
     * Server membership is determined by channel selection, never by URL presence elsewhere.
     * Concrete pointers deduplicate distinct root aliases of one server. An unresolved selected
     * server makes the set incomplete: its protocol is unknown, rather than known to be non-Kafka.
     */
    private ServerContext servers(ResolvedReference channel) {
        List<String> uses = new ArrayList<>();
        if (!channel.node().path(SERVERS).isEmpty()) {
            for (int i = 0; i < channel.node().path(SERVERS).size(); i++)
                uses.add(channel.pointer() + "/" + SERVERS + "/" + i);
        } else {
            for (var member : source.root.path(SERVERS).properties())
                uses.add("/" + SERVERS + "/" + SourceDocument.escape(member.getKey()));
        }
        Map<String, ResolvedReference> kafka = new LinkedHashMap<>();
        boolean complete = true;
        for (String use : uses) {
            var server = references.resolve(use);
            if (server.isEmpty()) complete = false;
            else if (KAFKA_PROTOCOLS.contains(server.get().node().path(PROTOCOL).asText()))
                kafka.putIfAbsent(server.get().pointer(), server.get());
        }
        return new ServerContext(List.copyOf(kafka.values()), complete);
    }

    /** A known bad server still reports even if another server in the same context is unresolved. */
    private void validateMetadata(String operation, String channel, String field, ServerContext servers) {
        if (servers.kafka().isEmpty()) {
            // Do not turn an unresolved server into a second, misleading "no Kafka" warning.
            if (servers.complete()) {
                Finding warning = finding(field, "", Severity.WARNING,
                        "Kafka registry metadata cannot be checked: this usage has no Kafka server");
                warning.related.add(source.location(operation));
                warning.related.add(source.location(channel));
            }
            return;
        }
        for (ResolvedReference server : servers.kafka()) {
            String bindings = server.pointer() + "/" + BINDINGS;
            // No map is a known lack of URL. An invalid explicit map is unknown and already
            // has its reference error; treating both as absent would create secondary noise.
            if (server.node().has(BINDINGS) && references.resolve(bindings).isEmpty()) continue;
            boolean hasUrl = kafkaBinding(server.pointer()).map(b -> b.node().has(REGISTRY_URL)).orElse(false);
            if (!hasUrl) {
                Finding error = finding(field, server.pointer(), Severity.ERROR,
                        "Kafka message registry metadata requires schemaRegistryUrl on every Kafka server in its usage context");
                error.related.add(source.location(server.pointer()));
                error.related.add(source.location(operation));
            }
        }
    }

    /**
     * Resolves the whole map before appending the protocol; refs are not protocol entries.
     * Reading only concrete targets also prevents ignored siblings of owner/map refs from
     * creating a Registry requirement or satisfying a missing URL.
     */
    private Optional<ResolvedReference> kafkaBinding(String owner) {
        return references.resolve(owner + "/" + BINDINGS).flatMap(map ->
                map.node().has(KAFKA) ? references.resolve(map.pointer() + "/" + KAFKA) : Optional.empty());
    }

    /** Reuses the accumulator so a shared binding can retain all affected operations. */
    private Finding finding(String field, String server, Severity severity, String message) {
        return findings.computeIfAbsent(new FindingKey(field, server),
                ignored -> new Finding(field, severity, message));
    }

    /**
     * Aliases and repeated operations aggregate into one finding per binding/server pair.
     * The empty server pointer denotes a binding-local vendor check or a no-server warning;
     * their distinct primary fields keep those findings separate from each other.
     */
    private record FindingKey(String field, String server) { }
    /** Known Kafka definitions plus whether every selected server's protocol could be determined. */
    private record ServerContext(List<ResolvedReference> kafka, boolean complete) { }

    /** Mutable only within this parse; the exported Diagnostic snapshots sorted, unique locations. */
    private final class Finding {
        private final String field;
        private final Severity severity;
        private final String message;
        private final Set<SourceLocation> related = new LinkedHashSet<>();

        private Finding(String field, Severity severity, String message) {
            this.field = field;
            this.severity = severity;
            this.message = message;
        }

        private Diagnostic diagnostic() {
            // Stable pointer order keeps related locations independent of alias/operation visit order.
            return Diagnostic.at(BuiltinRuleId.KAFKA_SCHEMA_REGISTRY, severity, message, source.location(field),
                    related.stream().sorted(Comparator.comparing(SourceLocation::pointer)).toList());
        }
    }
}
