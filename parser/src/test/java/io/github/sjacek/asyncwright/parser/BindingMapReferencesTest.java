package io.github.sjacek.asyncwright.parser;

import com.asyncapi.bindings.amqp.AMQPChannelBinding;
import com.asyncapi.bindings.amqp.AMQPMessageBinding;
import com.asyncapi.bindings.amqp.AMQPOperationBinding;
import com.asyncapi.bindings.amqp.AMQPServerBinding;
import com.asyncapi.schemas.asyncapi.Reference;
import com.asyncapi.v3._0_0.model.channel.Channel;
import com.asyncapi.v3._0_0.model.channel.message.Message;
import com.asyncapi.v3._0_0.model.operation.Operation;
import com.asyncapi.v3._0_0.model.server.Server;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sjacek.asyncwright.api.AsyncApiDocumentView;
import io.github.sjacek.asyncwright.api.AsyncApiGenerator;
import io.github.sjacek.asyncwright.api.AsyncApiRule;
import io.github.sjacek.asyncwright.api.Diagnostic;
import io.github.sjacek.asyncwright.api.ExtensionContext;
import io.github.sjacek.asyncwright.api.ExtensionSpec;
import io.github.sjacek.asyncwright.api.GeneratorContext;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end binding-map regressions: models retain reference URIs, while a source-backed
 * resolver handles aliases, target kinds and diagnostics. The four owner kinds share these
 * requirements; tests assert observable model values and locations rather than index internals.
 */
class BindingMapReferencesTest {
    @TempDir
    Path output;

    enum Kind {
        SERVER("/servers/broker", "serverBindings", AMQPServerBinding.class),
        CHANNEL("/channels/events", "channelBindings", AMQPChannelBinding.class),
        OPERATION("/operations/send", "operationBindings", AMQPOperationBinding.class),
        MESSAGE("/components/messages/Event", "messageBindings", AMQPMessageBinding.class);

        final String owner;
        final String field;
        final Class<?> protocolType;

        Kind(String owner, String field, Class<?> protocolType) {
            this.owner = owner;
            this.field = field;
            this.protocolType = protocolType;
        }

        String target() { return "/components/" + field + "/Shared"; }

        Map<String, Object> components(AsyncApiDocumentView document) {
            var components = Objects.requireNonNull(document.model().getComponents());
            return Objects.requireNonNull(switch (this) {
                case SERVER -> components.getServerBindings();
                case CHANNEL -> components.getChannelBindings();
                case OPERATION -> components.getOperationBindings();
                case MESSAGE -> components.getMessageBindings();
            });
        }

        Map<String, Object> ownerBindings(AsyncApiDocument document) throws Exception {
            return Objects.requireNonNull(switch (this) {
                case SERVER -> document.references().resolve(owner, Server.class).orElseThrow().getBindings();
                case CHANNEL -> document.references().resolve(owner, Channel.class).orElseThrow().getBindings();
                case OPERATION -> document.references().resolve(owner, Operation.class).orElseThrow().getBindings();
                case MESSAGE -> document.references().resolve(owner, Message.class).orElseThrow().getBindings();
            });
        }

        Map<String, Object> modelBindings(AsyncApiDocumentView document) {
            var model = document.model();
            return Objects.requireNonNull(switch (this) {
                case SERVER -> assertInstanceOf(Server.class, Objects.requireNonNull(model.getServers()).get("broker")).getBindings();
                case CHANNEL -> assertInstanceOf(Channel.class, Objects.requireNonNull(model.getChannels()).get("events")).getBindings();
                case OPERATION -> assertInstanceOf(Operation.class, Objects.requireNonNull(model.getOperations()).get("send")).getBindings();
                case MESSAGE -> assertInstanceOf(Message.class, Objects.requireNonNull(Objects.requireNonNull(model.getComponents())
                        .getMessages()).get("Event")).getBindings();
            });
        }
    }

    private static ParseResult parse(Kind kind, String variant) throws Exception {
        String file = "synthetic/binding-map-" + kind.name().toLowerCase(java.util.Locale.ROOT) + "-" + variant + ".json";
        try (var input = Objects.requireNonNull(BindingMapReferencesTest.class.getResourceAsStream("/conformance/" + file))) {
            return new AsyncApiParser().parse(file, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** Reuses the public positive fixture, then changes only the condition under test. */
    private static ObjectNode aliasSource(Kind kind) throws Exception {
        return assertInstanceOf(ObjectNode.class, parse(kind, "aliases").document().orElseThrow().source());
    }

    private static ObjectNode object(ObjectNode root, String pointer) {
        return assertInstanceOf(ObjectNode.class, root.at(pointer), pointer);
    }

    private static ParseResult parse(ObjectNode source) {
        return new AsyncApiParser().parse("binding-map.json", source.toPrettyString());
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void keepsTypedProtocolMapsAndAliasesWithoutExpandingTheSource(Kind kind) throws Exception {
        for (String variant : List.of("direct", "aliases")) {
            var result = parse(kind, variant);
            assertTrue(result.successful(), result.diagnostics()::toString);
            var document = result.document().orElseThrow();
            var source = document.source();
            String alias = variant.equals("direct") ? "Shared" : "Alias2";
            assertEquals(Map.of("$ref", "#/components/" + kind.field + "/" + alias), kind.ownerBindings(document));
            assertEquals(kind.ownerBindings(document), kind.modelBindings(document));
            var entries = kind.components(document);
            assertEquals("#" + kind.target(), assertInstanceOf(Reference.class, entries.get("Alias")).getRef());
            var shared = assertInstanceOf(Map.class, entries.get("Shared"));
            assertInstanceOf(kind.protocolType, shared.get("amqp"));

            var target = document.references().resolve(kind.owner + "/bindings").orElseThrow();
            assertEquals(kind.target(), target.pointer());
            assertEquals(source.at(kind.target()), target.node());
            assertTrue(document.references().resolve(target.pointer() + "/amqp", kind.protocolType).isPresent());
            // Neither resolving a protocol nor mutating the returned copy can flatten a model reference.
            ((com.fasterxml.jackson.databind.node.ObjectNode) target.node()).removeAll();
            assertEquals(source, document.source());
            assertTrue(kind.ownerBindings(document).containsKey("$ref"));
            assertTrue(document.references().related(kind.target() + "/amqp").stream()
                    .anyMatch(location -> location.pointer().equals(kind.owner + "/bindings/$ref")));
        }
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void diagnosesTheUseAndDefinitionWithoutReturningAPartialModel(Kind kind) throws Exception {
        for (var invalid : Map.of("missing", "reference.missing", "wrong-kind", "reference.type",
                "cycle", "reference.cycle", "bad-pointer", "reference.pointer", "bad-definition",
                kind == Kind.SERVER ? "model.mapping" : "schema.additionalProperties").entrySet()) {
            var result = parse(kind, invalid.getKey());
            assertFalse(result.successful(), invalid.getKey());
            assertTrue(result.document().isEmpty());
            var diagnostic = result.diagnostics().stream().filter(d -> d.ruleId().equals(invalid.getValue()))
                    .findFirst().orElseThrow(() -> new AssertionError(result.diagnostics()));
            assertTrue(diagnostic.line() > 1 && diagnostic.column() > 0);
            if (invalid.getKey().equals("cycle")) {
                assertFalse(diagnostic.related().isEmpty());
                assertTrue(diagnostic.pointer().startsWith("/components/" + kind.field + "/"));
            } else if (invalid.getKey().equals("bad-definition")) {
                assertEquals(kind.target() + "/amqp" + (kind == Kind.SERVER ? "/unexpected" : ""), diagnostic.pointer());
                assertTrue(diagnostic.related().stream().anyMatch(location ->
                        location.pointer().equals(kind.owner + "/bindings/$ref")));
            } else assertEquals(kind.owner + "/bindings/$ref", diagnostic.pointer());
        }
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void extensionsReceiveIndependentReferenceModelsAndUseTheSameResolver(Kind kind) throws Exception {
        var parsed = parse(kind, "aliases");
        var original = parsed.document().orElseThrow().source();
        var parameters = Map.of("owner", kind.owner, "kind", kind.name());
        var spec = new ExtensionSpec(BindingGenerator.class.getName(), parameters);
        var generated = new ExtensionExecutor().generate(parsed,
                List.of(new ExtensionSpec(MutatingBindingRule.class.getName(), parameters)), List.of(spec), output);
        assertTrue(generated.successful(), generated.diagnostics()::toString);
        assertEquals(kind.target(), Files.readString(output.resolve("binding.txt")));
        assertEquals(original, parsed.document().orElseThrow().source());
        assertTrue(kind.modelBindings(parsed.document().orElseThrow()).containsKey("$ref"));
        assertFalse(kind.components(parsed.document().orElseThrow()).isEmpty());
    }

    /** Full-document acceptance uses names permitted by the Components Object name pattern. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void resolvesPercentEncodedValidComponentNamesWithoutRewritingAliases(Kind kind) throws Exception {
        var source = aliasSource(kind);
        var entries = object(source, "/components/" + kind.field);
        entries.set("Shared.Name-1_2", entries.remove("Shared"));
        String uri = "#/components/" + kind.field + "/%53hared%2EName%2D1%5F2";
        object(entries, "/Alias").put("$ref", uri);
        var result = parse(source);
        assertTrue(result.successful(), result.diagnostics()::toString);
        var document = result.document().orElseThrow();
        var target = document.references().resolve(kind.owner + "/bindings").orElseThrow();
        assertEquals("/components/" + kind.field + "/Shared.Name-1_2", target.pointer());
        assertEquals(uri, assertInstanceOf(Reference.class, kind.components(document).get("Alias")).getRef());
        assertTrue(document.references().resolve(target.pointer() + "/amqp", kind.protocolType).isPresent());
        assertEquals(source, document.source());
    }

    /**
     * Resolver-only grammar check: arbitrary JSON Pointer tokens are independent of component-name
     * validity. This bypasses structural validation deliberately and makes no document acceptance claim.
     */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void decodesEscapedPointerTokensAndPreservesLiteralPlusSigns(Kind kind) throws Exception {
        var tree = aliasSource(kind);
        var entries = object(tree, "/components/" + kind.field);
        entries.set("a/b~ c+d", entries.remove("Shared"));
        String uri = "#/components/" + kind.field + "/a~1b~0%20c+d";
        object(entries, "/Alias").put("$ref", uri);
        var source = SourceDocument.read("pointer-grammar.json", tree.toPrettyString());
        var resolver = new ReferenceResolver(source, new DocumentIndex(source));
        assertTrue(resolver.validate().isEmpty());
        var target = resolver.resolve(kind.owner + "/bindings").orElseThrow();
        assertEquals("/components/" + kind.field + "/a~1b~0 c+d", target.pointer());
        assertEquals(tree.at(target.pointer()), target.node());
        assertTrue(resolver.resolve(target.pointer() + "/amqp", kind.protocolType).isPresent());
        assertEquals(uri, source.root.at("/components/" + kind.field + "/Alias/$ref").asText());
        assertEquals(tree, source.root);
    }

    /** Ignored siblings must neither reach protocol mapping nor introduce external reference edges. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void warnsAboutBindingReferenceSiblingsButPreservesTheirSource(Kind kind) throws Exception {
        var source = aliasSource(kind);
        var binding = object(source, kind.owner + "/bindings");
        binding.putObject("amqp").put("unexpected", true);
        binding.putObject("x-ignored").put("$ref", "https://example.invalid/unused.json");
        var result = parse(source);
        assertTrue(result.successful(), result.diagnostics()::toString);
        assertEquals(List.of("reference.siblings"), result.diagnostics().stream().map(Diagnostic::ruleId).toList());
        assertEquals(kind.owner + "/bindings", result.diagnostics().get(0).pointer());
        var document = result.document().orElseThrow();
        assertEquals(Map.of("$ref", binding.path("$ref").asText()), kind.modelBindings(document));
        assertEquals(source, document.source());
        assertEquals(kind.target(), document.references().resolve(kind.owner + "/bindings").orElseThrow().pointer());
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void diagnosesAWrongKindAtTheComponentAliasRatherThanItsValidOwnerUse(Kind kind) throws Exception {
        var source = aliasSource(kind);
        Kind other = Kind.values()[(kind.ordinal() + 1) % Kind.values().length];
        object(source, "/components/" + kind.field + "/Alias").put("$ref", "#" + other.target());
        var result = parse(source);
        assertFalse(result.successful());
        assertTrue(result.document().isEmpty());
        assertEquals(List.of("reference.type"), result.diagnostics().stream().map(Diagnostic::ruleId).toList());
        assertEquals("/components/" + kind.field + "/Alias/$ref", result.diagnostics().get(0).pointer());
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void rejectsSelfReferentialBindingMapsWithoutAConcreteDefinition(Kind kind) throws Exception {
        var source = aliasSource(kind);
        var shared = object(source, kind.target());
        shared.removeAll();
        shared.put("$ref", "#" + kind.target());
        var result = parse(source);
        assertFalse(result.successful());
        assertTrue(result.document().isEmpty());
        var cycle = result.diagnostics().stream().filter(d -> d.ruleId().equals("reference.cycle")).findFirst().orElseThrow();
        assertEquals(kind.target() + "/$ref", cycle.pointer());
        assertTrue(cycle.related().stream().anyMatch(location -> location.pointer().equals(kind.target())));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void rejectsExternalBindingMapReferencesAtTheUsePosition(Kind kind) throws Exception {
        var source = aliasSource(kind);
        object(source, kind.owner + "/bindings").put("$ref", "https://example.invalid/maps.json#/Shared");
        var result = parse(source);
        assertFalse(result.successful());
        assertTrue(result.document().isEmpty());
        assertEquals(List.of("unsupported.reference"), result.diagnostics().stream().map(Diagnostic::ruleId).toList());
        assertEquals(kind.owner + "/bindings/$ref", result.diagnostics().get(0).pointer());
    }

    /** Deliberately damages its own model; subsequent callbacks must receive independent maps. */
    public static final class MutatingBindingRule implements AsyncApiRule {
        @Override
        public List<Diagnostic> validate(ExtensionContext context) {
            Kind kind = Kind.valueOf(context.requireParameter("kind"));
            kind.modelBindings(context.document()).clear();
            kind.components(context.document()).clear();
            assertEquals(kind.target(), context.document().references()
                    .resolve(context.requireParameter("owner") + "/bindings").orElseThrow().pointer());
            return List.of();
        }
    }

    /** Uses only the public extension API, as a consumer's generator would. */
    public static final class BindingGenerator implements AsyncApiGenerator {
        @Override
        public List<Diagnostic> generate(GeneratorContext context) throws Exception {
            Kind kind = Kind.valueOf(context.requireParameter("kind"));
            assertTrue(kind.modelBindings(context.document()).containsKey("$ref"));
            assertInstanceOf(Map.class, kind.components(context.document()).get("Shared"));
            var target = context.document().references().resolve(context.requireParameter("owner") + "/bindings").orElseThrow();
            assertTrue(target.node().has("amqp"));
            context.output().writeUtf8("binding.txt", target.pointer());
            return List.of();
        }
    }
}
