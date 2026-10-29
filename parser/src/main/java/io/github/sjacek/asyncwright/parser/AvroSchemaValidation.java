package io.github.sjacek.asyncwright.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sjacek.asyncwright.api.BuiltinRuleId;
import io.github.sjacek.asyncwright.api.Severity;
import org.apache.avro.AvroRuntimeException;
import org.apache.avro.LogicalTypes;
import org.apache.avro.NameValidator;
import org.apache.avro.Schema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.sjacek.asyncwright.api.AsyncApiFields.*;

/**
 * Applies the Avro 1.9 authoring profile to one finite schema tree.
 * Apache Avro checks grammar; the additional passes retain 1.9 name/default rules and make
 * logical-type fallback explicit. File authority and diagnostics with source locations belong
 * to {@link AvroValidation}. No file is read, no message instance is validated, and the caller's
 * tree is never modified. Instances hold traversal state and must not be reused between schemas.
 */
final class AvroSchemaValidation {
    private static final String FIELDS = "fields";
    private static final String VALUES = "values";
    private static final String LOGICAL_TYPE = "logicalType";
    private static final Set<String> LOGICAL_TYPES = Set.of("decimal", "uuid", "date", "time-millis", "time-micros",
            "timestamp-millis", "timestamp-micros", "duration");

    record Problem(BuiltinRuleId rule, Severity severity, String message, String pointer) { }
    private final List<Problem> problems = new ArrayList<>();
    // Avro field/schema equality can traverse recursive definitions. Use parser-node identity,
    // and collect every default before validating any record default that may refer to it.
    private final Map<Schema.Field, JsonNode> defaults = new IdentityHashMap<>();
    private final Map<Schema.Field, String> defaultPointers = new IdentityHashMap<>();

    /** Returns relative-pointer problems; unexpected implementation failures are allowed to propagate. */
    List<Problem> validate(JsonNode input) {
        try {
            new AvroNames().validate(input);
            JsonNode validation = input.deepCopy();
            stripLogicalTypes(validation);
            // New parser per standalone schema: one file or wrapper cannot supply names to another.
            // 1.12's UTF names and any-union default matching are broader than the 1.9 contract.
            Schema schema = new Schema.Parser(NameValidator.STRICT_VALIDATOR).setValidateDefaults(true).parse(validation.toString());
            inspect(schema, input, "");
            for (var entry : defaults.entrySet()) {
                if (!validDefault(entry.getKey().schema(), entry.getValue(), new IdentityHashMap<>()))
                    problems.add(new Problem(BuiltinRuleId.AVRO_SCHEMA, Severity.ERROR,
                            "Default must match its Avro 1.9 type, including the first branch of every union",
                            defaultPointers.get(entry.getKey())));
            }
        } catch (AvroRuntimeException | IllegalArgumentException exception) {
            problems.add(new Problem(BuiltinRuleId.AVRO_SCHEMA, Severity.ERROR,
                    "Invalid Avro schema: " + exception.getMessage(), ""));
        }
        return List.copyOf(problems);
    }

    /** Remove annotations only from real schema positions, never defaults or arbitrary metadata. */
    private static void stripLogicalTypes(JsonNode node) {
        if (node.isArray()) { node.forEach(AvroSchemaValidation::stripLogicalTypes); return; }
        if (!(node instanceof ObjectNode object)) return;
        object.remove(LOGICAL_TYPE);
        JsonNode type = node.path(TYPE);
        if (type.isArray() || type.isObject()) stripLogicalTypes(type);
        if (type.asText().equals("record") || type.asText().equals("error"))
            node.path(FIELDS).forEach(field -> stripLogicalTypes(field.path(TYPE)));
        if (type.asText().equals("array")) stripLogicalTypes(node.path(ITEMS));
        if (type.asText().equals("map")) stripLogicalTypes(node.path(VALUES));
    }

    /** The original finite syntax tree supplies paths; named references do not expand recursion. */
    private void inspect(Schema schema, JsonNode node, String pointer) {
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) inspect(schema.getTypes().get(i), node.path(i), pointer + "/" + i);
            return;
        }
        if (!node.isObject()) return;
        if (node.has(LOGICAL_TYPE)) logicalType(schema, node.path(LOGICAL_TYPE), pointer + "/" + LOGICAL_TYPE);
        if (node.path(TYPE).isContainerNode()) { inspect(schema, node.path(TYPE), pointer + "/" + TYPE); return; }
        switch (schema.getType()) {
            case RECORD -> {
                for (int i = 0; i < node.path(FIELDS).size(); i++) {
                    JsonNode field = node.path(FIELDS).path(i);
                    Schema.Field parsed = schema.getFields().get(i);
                    String path = pointer + "/" + FIELDS + "/" + i;
                    if (field.has(DEFAULT)) {
                        defaults.put(parsed, field.path(DEFAULT));
                        defaultPointers.put(parsed, path + "/" + DEFAULT);
                    }
                    inspect(parsed.schema(), field.path(TYPE), path + "/" + TYPE);
                }
            }
            case ARRAY -> inspect(schema.getElementType(), node.path(ITEMS), pointer + "/" + ITEMS);
            case MAP -> inspect(schema.getValueType(), node.path(VALUES), pointer + "/" + VALUES);
            default -> { }
        }
    }

    /** Known invalid annotations are authoring errors; unknown/newer ones retain their base type. */
    private void logicalType(Schema schema, JsonNode type, String pointer) {
        if (!type.isTextual()) {
            problems.add(new Problem(BuiltinRuleId.AVRO_LOGICAL_TYPE, Severity.ERROR, "Avro logicalType must be a string", pointer));
        } else if (!LOGICAL_TYPES.contains(type.asText())) {
            problems.add(new Problem(BuiltinRuleId.AVRO_LOGICAL_TYPE, Severity.WARNING,
                    "Unknown Avro 1.9 logical type is treated as its underlying type", pointer));
        } else {
            try {
                // Only the validation copy is annotated. Avoid the library's silent fallback/logging
                // for invalid known logical types, and avoid interpreting post-1.9 logical types.
                schema.addProp(LOGICAL_TYPE, type.asText());
                var logical = LogicalTypes.fromSchema(schema);
                if (logical == null) throw new IllegalArgumentException("Logical type is unavailable");
                logical.validate(schema);
            } catch (IllegalArgumentException exception) {
                problems.add(new Problem(BuiltinRuleId.AVRO_LOGICAL_TYPE, Severity.ERROR,
                        "Invalid Avro 1.9 logical type: " + exception.getMessage(), pointer));
            }
        }
    }

    /**
     * Checks declared defaults, including nested first-union-branch rules.
     * The active set is a recursion stack, not global memoization: equal sibling values are legal,
     * but a recursive fallback to the same record/default must terminate. Missing record values
     * may use that field's declared default; explicit JSON null is never treated as missing.
     */
    private boolean validDefault(Schema schema, JsonNode value, Map<Schema, Set<JsonNode>> active) {
        if (value == null || value.isMissingNode()) return false;
        Set<JsonNode> values = active.computeIfAbsent(schema, ignored -> new HashSet<>());
        if (!values.add(value)) return false; // A recursive record default must terminate.
        try {
            return switch (schema.getType()) {
                case NULL -> value.isNull();
                case BOOLEAN -> value.isBoolean();
                case INT -> value.isIntegralNumber() && value.canConvertToInt();
                case LONG -> value.isIntegralNumber() && value.canConvertToLong();
                case FLOAT, DOUBLE -> value.isNumber();
                case STRING -> value.isTextual();
                case BYTES -> value.isTextual() && value.asText().chars().allMatch(c -> c <= 255);
                case FIXED -> value.isTextual() && value.asText().length() == schema.getFixedSize()
                        && value.asText().chars().allMatch(c -> c <= 255);
                case ENUM -> value.isTextual() && schema.hasEnumSymbol(value.asText());
                case UNION -> validDefault(schema.getTypes().get(0), value, active);
                case ARRAY -> {
                    boolean valid = value.isArray();
                    for (JsonNode item : value) valid &= validDefault(schema.getElementType(), item, active);
                    yield valid;
                }
                case MAP -> {
                    boolean valid = value.isObject();
                    for (JsonNode item : value) valid &= validDefault(schema.getValueType(), item, active);
                    yield valid;
                }
                case RECORD -> {
                    boolean valid = value.isObject();
                    for (Schema.Field field : schema.getFields()) valid &= validDefault(field.schema(),
                            value.has(field.name()) ? value.path(field.name()) : defaults.get(field), active);
                    yield valid;
                }
            };
        } finally { values.remove(value); }
    }
}
