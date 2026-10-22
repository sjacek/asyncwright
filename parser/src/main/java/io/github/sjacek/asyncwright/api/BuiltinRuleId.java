package io.github.sjacek.asyncwright.api;

/** Built-in diagnostic identifiers; custom extension IDs remain unrestricted strings. */
public enum BuiltinRuleId {
    /**
     * Invalid JSON/YAML syntax or duplicate mapping keys.
     */
    SYNTAX("syntax"),
    /**
     * YAML alias outside the supported input subset.
     */
    UNSUPPORTED_YAML_ALIAS("unsupported.yaml-alias"),
    /**
     * Unsupported AsyncAPI specification version.
     */
    UNSUPPORTED_VERSION("unsupported.version"),
    /**
     * Construct outside the implemented capability boundary.
     */
    UNSUPPORTED_CONSTRUCT("unsupported.construct"),
    /**
     * Unsupported JSON Schema dialect.
     */
    UNSUPPORTED_SCHEMA_DIALECT("unsupported.schema-dialect"),
    /**
     * Schema identifier requiring unsupported scope resolution.
     */
    UNSUPPORTED_SCHEMA_ID("unsupported.schema-id"),
    /**
     * External reference outside the local-document boundary.
     */
    UNSUPPORTED_REFERENCE("unsupported.reference"),
    /**
     * Ignored properties beside a Reference Object target (warning).
     */
    REFERENCE_SIBLINGS("reference.siblings"),
    /**
     * Reference target does not exist.
     */
    REFERENCE_MISSING("reference.missing"),
    /**
     * Reference targets the wrong semantic object kind.
     */
    REFERENCE_TYPE("reference.type"),
    /**
     * Malformed reference URI or JSON Pointer escape.
     */
    REFERENCE_POINTER("reference.pointer"),
    /**
     * Reference cycle without a concrete recursive schema definition.
     */
    REFERENCE_CYCLE("reference.cycle"),
    /**
     * Typed model deserialization failure.
     */
    MODEL_MAPPING("model.mapping"),
    /**
     * Typed model would silently lose source information.
     */
    MODEL_PRESERVATION("model.preservation"),
    /**
     * Channel parameter declaration and address template disagree.
     */
    CHANNEL_PARAMETER("channel.parameter"),
    /**
     * Channel server reference does not target a root server.
     */
    CHANNEL_SERVER("channel.server"),
    /**
     * Root operation channel does not reference a root channel.
     */
    OPERATION_CHANNEL("operation.channel"),
    /**
     * Operation message is not a member of its channel.
     */
    OPERATION_MESSAGE("operation.message"),
    /**
     * Project channel address pattern mismatch.
     */
    PROJECT_CHANNEL_ADDRESS("project.channel-address"),
    /**
     * Project default content type requirement mismatch.
     */
    PROJECT_DEFAULT_CONTENT_TYPE("project.default-content-type"),
    /**
     * Declared server protocol violates a project requirement.
     */
    PROJECT_PROTOCOL("project.protocol");

    private final String id;
    BuiltinRuleId(String id) { this.id = id; }
    /**
     * Returns the stable identifier emitted in reports.
     * @return wire identifier independent of the Java enum name
     */
    public String id() { return id; }
    /**
     * Builds an identifier for a structural schema validator finding.
     * @param keyword validator keyword, for example {@code required}
     * @return identifier prefixed with {@code schema.}
     */
    public static String schemaKeyword(String keyword) { return "schema." + keyword; }
}
