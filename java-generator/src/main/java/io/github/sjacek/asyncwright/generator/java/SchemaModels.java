package io.github.sjacek.asyncwright.generator.java;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.sjacek.asyncwright.api.AsyncApiDocumentView;
import io.github.sjacek.asyncwright.api.ResolvedReference;

import java.io.Serial;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Invocation-local type graph, keyed by resolved definition pointers rather than reference usages.
 * Linked maps preserve declaration/discovery order for deterministic source output.
 * A model is registered before its fields, allowing concrete recursion without expanding the schema.
 * Container recursion is tracked separately because it cannot always be expressed as a Java type.
 */
final class SchemaModels {
    static final String UNSUPPORTED = "java-dto.unsupported";
    static final String NAME = "java-dto.name";
    static final String COLLISION = "java-dto.collision";
    private static final String COMPONENT_SCHEMAS = "/components/schemas/";
    private static final List<String> COMPOSITIONS = List.of("allOf", "anyOf", "oneOf");
    private final AsyncApiDocumentView document;
    private final JavaDtoGenerator.Settings settings;
    private final JsonNode source;
    private final Map<String, Model> models = new LinkedHashMap<>();
    private final Map<String, String> names = new LinkedHashMap<>();
    private final Set<String> visitedMessages = new HashSet<>();
    private final Set<String> visitingTypes = new HashSet<>();

    SchemaModels(AsyncApiDocumentView document, JavaDtoGenerator.Settings settings) {
        this.document = document;
        this.settings = settings;
        source = document.source();
    }

    /** Discovers message roots, completes every model, then renders; a profile error writes nothing. */
    List<JavaSource.File> compile() {
        visitMessages(source.at("/components/messages"), "/components/messages");
        JsonNode channels = source.path("channels");
        channels.properties().forEach(channel -> {
            Target target = target("/channels/" + JavaNames.token(channel.getKey()));
            visitMessages(target.node.path("messages"), target.pointer + "/messages");
        });
        if (models.isEmpty()) throw failure(UNSUPPORTED, "", "No object or enum message payloads to generate");
        return models.values().stream().map(model -> JavaSource.render(model, models, settings.packageName())).toList();
    }

    /** A message reached through several channel/component aliases still contributes only one root. */
    private void visitMessages(JsonNode messages, String pointer) {
        messages.properties().forEach(entry -> {
            Target message = target(pointer + "/" + JavaNames.token(entry.getKey()));
            if (!visitedMessages.add(message.pointer) || !message.node.has("payload")) return;
            String payload = message.pointer + "/payload";
            Target schema = target(payload);
            String name = componentName(schema.pointer, entry.getKey() + "Payload");
            String type = schemaType(schema);
            if (!type.equals("object") && !schema.node.has("enum"))
                throw failure(UNSUPPORTED, payload, "A Java DTO root must be an object or string enum");
            model(schema, name);
        });
    }

    /** Keeps pointer decoding, alias chains and cycle policy in the shared public resolver. */
    private Target target(String pointer) {
        ResolvedReference resolved = document.references().resolve(pointer)
                .orElseThrow(() -> failure(UNSUPPORTED, pointer, "Cannot resolve schema or message"));
        return new Target(resolved.pointer(), resolved.node());
    }

    /** Component keys own public type names; nested/inline schemas use an owning-property hint. */
    private String componentName(String pointer, String fallback) {
        if (pointer.startsWith(COMPONENT_SCHEMAS)) {
            String token = pointer.substring(COMPONENT_SCHEMAS.length());
            if (!token.contains("/")) return token.replace("~1", "/").replace("~0", "~");
        }
        return fallback;
    }

    private Model model(Target schema, String hint) {
        Model existing = models.get(schema.pointer);
        if (existing != null) return existing;
        String name = componentName(schema.pointer, hint);
        if (!JavaNames.typeName(name)) throw failure(NAME, schema.pointer, "Invalid Java type name: " + name);
        String previous = names.putIfAbsent(name.toLowerCase(Locale.ROOT), schema.pointer);
        if (previous != null && !previous.equals(schema.pointer))
            throw failure(COLLISION, schema.pointer, "Java type name collides with " + previous + ": " + name);
        String type = schemaType(schema);
        // Register a placeholder before traversing fields: Node.children may point back to Node.
        Model model = new Model(name, schema.pointer, schema.node.has("enum"));
        models.put(schema.pointer, model);
        if (model.enumeration) {
            if (!type.equals("string")) throw failure(UNSUPPORTED, schema.pointer, "Only string enums are supported");
            for (JsonNode value : schema.node.path("enum")) {
                if (value.isNull()) continue; // JSON null remains a Java null reference, not a string enum member.
                if (!value.isTextual())
                    throw failure(NAME, schema.pointer + "/enum", "Enum values must be distinct Java identifiers");
                String symbol = value.textValue().toUpperCase(Locale.ROOT);
                if (!JavaNames.identifier(symbol))
                    throw failure(NAME, schema.pointer + "/enum", "Invalid enum constant: " + symbol);
                if (model.values.putIfAbsent(symbol, value.textValue()) != null)
                    throw failure(COLLISION, schema.pointer + "/enum", "Enum constant collides: " + symbol);
            }
            if (model.values.isEmpty()) throw failure(UNSUPPORTED, schema.pointer + "/enum", "Enum has no non-null members");
            return model;
        }
        if (!type.equals("object")) throw failure(UNSUPPORTED, schema.pointer, "Expected an object model");
        if (!schema.node.has("properties"))
            throw failure(UNSUPPORTED, schema.pointer, "DTO object roots need declared properties; dictionaries are supported as properties");
        Set<String> required = new HashSet<>();
        schema.node.path("required").forEach(value -> required.add(value.asText()));
        Set<String> accessors = new HashSet<>();
        schema.node.path("properties").properties().forEach(property -> {
            String field = property.getKey();
            String pointer = schema.pointer + "/properties/" + JavaNames.token(field);
            if (!JavaNames.property(field)) throw failure(NAME, pointer, "Property cannot retain its Java bean wire name: " + field);
            if (!accessors.add(JavaNames.capitalized(field)))
                throw failure(COLLISION, pointer, "Bean accessor collides for property: " + field);
            Target propertySchema = target(pointer);
            Type propertyType = type(propertySchema, name + JavaNames.capitalized(field), required.contains(field), false);
            model.fields.add(new Field(field, propertyType, constant(propertySchema, propertyType)));
        });
        // JSON Schema objects are open by default. Preserve the legacy map accessor, without
        // implying automatic JSON unknown-field binding or any-getter/any-setter annotations.
        JsonNode additional = schema.node.path("additionalProperties");
        if (!additional.isBoolean() || additional.booleanValue()) {
            if (accessors.contains(JavaNames.capitalized("additionalProperties")))
                throw failure(COLLISION, schema.pointer, "additionalProperties conflicts with the extra-value map");
            Type value = additional.isObject()
                    ? type(target(schema.pointer + "/additionalProperties"), name + "AdditionalValue", false, true)
                    : Type.scalar("Object");
            model.fields.add(new Field("additionalProperties", Type.map(value), null));
        }
        return model;
    }

    private Type type(Target schema, String hint, boolean required, boolean generic) {
        if (!visitingTypes.add(schema.pointer)) {
            // Concrete beans have an allocated Java name. A recursive container by itself
            // would require an infinite List<List<...>>/Map value type instead.
            Model existing = models.get(schema.pointer);
            if (existing != null) return Type.reference(existing);
            throw failure(UNSUPPORTED, schema.pointer, "Recursive containers need a concrete object model");
        }
        try {
            return typeValue(schema, hint, required, generic);
        } finally {
            visitingTypes.remove(schema.pointer);
        }
    }

    /** Generic positions always box primitives, even for non-null/required item schemas. */
    private Type typeValue(Target schema, String hint, boolean required, boolean generic) {
        String type = schemaType(schema);
        boolean primitive = required && !nullable(schema.node) && !generic;
        // Named enums expose their own Java API. Inline scalar constraints remain on the
        // contract and use the base Java type, like other non-runtime-validated constraints.
        if (schema.node.has("enum") && schema.pointer.startsWith(COMPONENT_SCHEMAS)) {
            Model model = model(schema, hint);
            return Type.reference(model);
        }
        return switch (type) {
            case "string" -> switch (schema.node.path("format").asText()) {
                case "uuid" -> Type.nativeType("UUID", "java.util.UUID");
                case "date" -> Type.nativeType("LocalDate", "java.time.LocalDate");
                case "date-time" -> Type.scalar("java.time.OffsetDateTime");
                default -> Type.scalar("String");
            };
            case "integer" -> {
                String format = schema.node.path("format").asText();
                if (!Set.of("", "int32", "int64").contains(format))
                    throw failure(UNSUPPORTED, schema.pointer + "/format", "Unsupported integer format: " + format);
                yield Type.scalar(format.equals("int64") ? (primitive ? "long" : "Long") : (primitive ? "int" : "Integer"));
            }
            case "number" -> Type.scalar(primitive ? "double" : "Double");
            case "boolean" -> Type.scalar(primitive ? "boolean" : "Boolean");
            case "array" -> {
                if (!schema.node.path("items").isObject())
                    throw failure(UNSUPPORTED, schema.pointer + "/items", "Array items must have a supported schema");
                Type item = type(target(schema.pointer + "/items"), hint + "Item", true, true);
                String collection = schema.node.path("uniqueItems").asBoolean()
                        ? JavaDtoGenerator.CollectionType.SET.spelling : settings.collectionType().spelling;
                yield Type.collection(collection, item);
            }
            case "object" -> {
                JsonNode additional = schema.node.path("additionalProperties");
                if (!schema.node.has("properties") && (!additional.isBoolean() || additional.booleanValue())) {
                    Type value = additional.isObject()
                            ? type(target(schema.pointer + "/additionalProperties"), hint + "Value", false, true)
                            : Type.scalar("Object");
                    yield Type.map(value);
                }
                yield Type.reference(model(schema, hint));
            }
            default -> throw failure(UNSUPPORTED, schema.pointer, "Unsupported Java DTO schema type: " + type);
        };
    }

    /** Accepts one Java-representable type, with optional null; never guesses a union/composition. */
    private String schemaType(Target schema) {
        if (!schema.node.isObject()) throw failure(UNSUPPORTED, schema.pointer, "Boolean and scalar schemas need an explicit DTO mapping");
        for (String key : COMPOSITIONS) {
            if (schema.node.has(key)) throw failure(UNSUPPORTED, schema.pointer + "/" + key, "Schema composition is outside the Java DTO profile");
        }
        JsonNode type = schema.node.path("type");
        if (type.isTextual()) return type.textValue();
        if (type.isArray()) {
            List<String> types = new ArrayList<>();
            type.forEach(value -> { if (!value.asText().equals("null")) types.add(value.asText()); });
            if (types.size() == 1) return types.get(0);
            throw failure(UNSUPPORTED, schema.pointer + "/type", "Only a single non-null type plus null is supported");
        }
        if (schema.node.has("properties")) return "object";
        throw failure(UNSUPPORTED, schema.pointer, "Schema needs an explicit type");
    }

    /** Requiredness and nullability are independent: a required nullable number is still boxed. */
    private static boolean nullable(JsonNode schema) {
        for (JsonNode type : schema.path("type")) if (type.asText().equals("null")) return true;
        return false;
    }

    /** Const properties are read-only bean state; unsupported literals fail instead of changing the API. */
    private static String constant(Target schema, Type type) {
        if (!schema.node.has("const")) return null;
        JsonNode value = schema.node.path("const");
        if (value.isNull() && !Set.of("int", "long", "double", "boolean").contains(type.text)) return "null";
        if (value.isTextual() && type.text.equals("String")) return "\"" + JavaNames.literal(value.textValue()) + "\"";
        if (value.isBoolean() && Set.of("boolean", "Boolean").contains(type.text)) return value.toString();
        if (value.isIntegralNumber() && Set.of("int", "Integer").contains(type.text) && value.canConvertToInt()) return value.toString();
        if (value.isIntegralNumber() && Set.of("long", "Long").contains(type.text) && value.canConvertToLong()) return value + "L";
        if (value.isNumber() && Set.of("double", "Double").contains(type.text) && Double.isFinite(value.doubleValue()))
            return Double.toString(value.doubleValue());
        throw failure(UNSUPPORTED, schema.pointer + "/const", "Constant has no supported Java literal for " + type.text);
    }

    private static Failure failure(String ruleId, String pointer, String message) {
        return new Failure(ruleId, pointer, message);
    }

    /** Canonical definition location and its unchanged structural schema. */
    record Target(String pointer, JsonNode node) { }
    /** A null constant means mutable state; the text "null" is a real read-only Java initializer. */
    record Field(String name, Type type, String constant) { }

    /** Captures type dependencies without inlining referenced classes, including recursive ones. */
    record Type(String text, Set<String> imports, Set<String> modelPointers) {
        static Type scalar(String text) { return new Type(text, Set.of(), Set.of()); }
        static Type nativeType(String text, String imported) { return new Type(text, Set.of(imported), Set.of()); }
        static Type reference(Model model) { return new Type(model.name, Set.of(), Set.of(model.pointer)); }
        static Type map(Type value) { return container("Map<String, " + value.text + ">", "java.util.Map", value); }
        static Type collection(String collection, Type item) {
            return container(collection + "<" + item.text + ">", "java.util." + collection, item);
        }
        private static Type container(String text, String imported, Type item) {
            Set<String> imports = new LinkedHashSet<>(item.imports);
            imports.add(imported);
            return new Type(text, imports, item.modelPointers);
        }
    }

    /** Mutable only during planning; never shared across generator calls or persisted in the document. */
    static final class Model {
        final String name;
        final String pointer;
        final boolean enumeration;
        final List<Field> fields = new ArrayList<>();
        final Map<String, String> values = new LinkedHashMap<>();
        Model(String name, String pointer, boolean enumeration) {
            this.name = name; this.pointer = pointer; this.enumeration = enumeration;
        }
    }

    /** A supported contract may still be outside a particular code generator's explicit profile. */
    static final class Failure extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;
        final String ruleId;
        final String pointer;
        Failure(String ruleId, String pointer, String message) {
            super(message); this.ruleId = ruleId; this.pointer = pointer;
        }
    }
}
