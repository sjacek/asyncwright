package io.github.sjacek.asyncwright.api;

/** Built-in diagnostic identifiers; custom extension IDs remain unrestricted strings. */
public enum BuiltinRuleId {
    SYNTAX("syntax"),
    UNSUPPORTED_YAML_ALIAS("unsupported.yaml-alias"),
    UNSUPPORTED_VERSION("unsupported.version"),
    UNSUPPORTED_CONSTRUCT("unsupported.construct"),
    UNSUPPORTED_SCHEMA_DIALECT("unsupported.schema-dialect"),
    UNSUPPORTED_SCHEMA_ID("unsupported.schema-id"),
    UNSUPPORTED_REFERENCE("unsupported.reference"),
    REFERENCE_SIBLINGS("reference.siblings"),
    REFERENCE_MISSING("reference.missing"),
    REFERENCE_TYPE("reference.type"),
    REFERENCE_POINTER("reference.pointer"),
    REFERENCE_CYCLE("reference.cycle"),
    MODEL_MAPPING("model.mapping"),
    MODEL_PRESERVATION("model.preservation"),
    CHANNEL_PARAMETER("channel.parameter"),
    CHANNEL_SERVER("channel.server"),
    OPERATION_CHANNEL("operation.channel"),
    OPERATION_MESSAGE("operation.message"),
    PROJECT_CHANNEL_ADDRESS("project.channel-address"),
    PROJECT_DEFAULT_CONTENT_TYPE("project.default-content-type"),
    PROJECT_PROTOCOL("project.protocol");

    private final String id;
    BuiltinRuleId(String id) { this.id = id; }
    public String id() { return id; }
    public static String schemaKeyword(String keyword) { return "schema." + keyword; }
}
