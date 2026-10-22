package io.github.sjacek.asyncwright.api;

/** AsyncAPI and JSON Schema wire names. Keep explicit values stable when Java names change. */
public final class AsyncApiFields {
    /**
     * Document specification version.
     */
    public static final String ASYNCAPI = "asyncapi";
    /**
     * Reference Object target URI.
     */
    public static final String REF = "$ref";
    /**
     * JSON Schema dialect declaration.
     */
    public static final String SCHEMA_DIALECT = "$schema";
    /**
     * JSON Schema identifier.
     */
    public static final String SCHEMA_ID = "$id";
    /**
     * Multi Format Schema Object media type.
     */
    public static final String SCHEMA_FORMAT = "schemaFormat";
    /**
     * Multi Format Schema Object value.
     */
    public static final String SCHEMA = "schema";
    /**
     * Reusable component definitions.
     */
    public static final String COMPONENTS = "components";
    /**
     * Operation or reply channel reference.
     */
    public static final String CHANNEL = "channel";
    /**
     * Channel definitions.
     */
    public static final String CHANNELS = "channels";
    /**
     * Operation definitions.
     */
    public static final String OPERATIONS = "operations";
    /**
     * Message definitions or references.
     */
    public static final String MESSAGES = "messages";
    /**
     * Server definitions or channel server references.
     */
    public static final String SERVERS = "servers";
    /**
     * Channel address parameters.
     */
    public static final String PARAMETERS = "parameters";
    /**
     * Channel address template.
     */
    public static final String ADDRESS = "address";
    /**
     * Server transport protocol.
     */
    public static final String PROTOCOL = "protocol";
    /**
     * Document default message content type.
     */
    public static final String DEFAULT_CONTENT_TYPE = "defaultContentType";
    /**
     * Reusable schema definitions.
     */
    public static final String SCHEMAS = "schemas";
    /**
     * Reusable security scheme definitions.
     */
    public static final String SECURITY_SCHEMES = "securitySchemes";
    /**
     * Message correlation identifier.
     */
    public static final String CORRELATION_ID = "correlationId";
    /**
     * Reusable correlation identifier definitions.
     */
    public static final String CORRELATION_IDS = "correlationIds";
    /**
     * Object classification tags.
     */
    public static final String TAGS = "tags";
    /**
     * Operation reply definition.
     */
    public static final String REPLY = "reply";
    /**
     * Protocol-specific object bindings.
     */
    public static final String BINDINGS = "bindings";
    /**
     * Reusable channel bindings.
     */
    public static final String CHANNEL_BINDINGS = "channelBindings";
    /**
     * Reusable operation bindings.
     */
    public static final String OPERATION_BINDINGS = "operationBindings";
    /**
     * Reusable message bindings.
     */
    public static final String MESSAGE_BINDINGS = "messageBindings";
    /**
     * Reusable server bindings.
     */
    public static final String SERVER_BINDINGS = "serverBindings";
    /**
     * Reusable operation traits.
     */
    public static final String OPERATION_TRAITS = "operationTraits";
    /**
     * Reusable message traits.
     */
    public static final String MESSAGE_TRAITS = "messageTraits";
    /**
     * Applied traits.
     */
    public static final String TRAITS = "traits";
    /**
     * Message payload schema.
     */
    public static final String PAYLOAD = "payload";
    /**
     * Message header schema.
     */
    public static final String HEADERS = "headers";
    /**
     * Named property schemas.
     */
    public static final String PROPERTIES = "properties";
    /**
     * Property schemas selected by regular expressions.
     */
    public static final String PATTERN_PROPERTIES = "patternProperties";
    /**
     * Draft-07 reusable subschemas.
     */
    public static final String DEFINITIONS = "definitions";
    /**
     * Reusable subschema container recognized during capability checks.
     */
    public static final String DEFS = "$defs";
    /**
     * Conjunction of subschemas.
     */
    public static final String ALL_OF = "allOf";
    /**
     * Exactly-one subschema alternative.
     */
    public static final String ONE_OF = "oneOf";
    /**
     * At-least-one subschema alternative.
     */
    public static final String ANY_OF = "anyOf";
    /**
     * Array item schema or tuple schemas.
     */
    public static final String ITEMS = "items";
    /**
     * Schema for additional tuple items.
     */
    public static final String ADDITIONAL_ITEMS = "additionalItems";
    /**
     * Schema for otherwise unmatched object properties.
     */
    public static final String ADDITIONAL_PROPERTIES = "additionalProperties";
    /**
     * Schema required of an array member.
     */
    public static final String CONTAINS = "contains";
    /**
     * Schema for object property names.
     */
    public static final String PROPERTY_NAMES = "propertyNames";
    /**
     * Negated subschema.
     */
    public static final String NOT = "not";
    /**
     * Conditional test subschema.
     */
    public static final String IF = "if";
    /**
     * Subschema applied when the conditional test matches.
     */
    public static final String THEN = "then";
    /**
     * Subschema applied when the conditional test fails.
     */
    public static final String ELSE = "else";
    /**
     * Property or schema dependencies.
     */
    public static final String DEPENDENCIES = "dependencies";
    /**
     * Schema or message example values.
     */
    public static final String EXAMPLES = "examples";
    /**
     * Single annotation example value.
     */
    public static final String EXAMPLE = "example";
    /**
     * Schema default instance value.
     */
    public static final String DEFAULT = "default";
    /**
     * Schema constant instance value.
     */
    public static final String CONST = "const";
    /**
     * Schema allowed instance values.
     */
    public static final String ENUM = "enum";
    /**
     * Schema instance type.
     */
    public static final String TYPE = "type";
    /**
     * Prefix reserved for specification extensions.
     */
    public static final String EXTENSION_PREFIX = "x-";
    private AsyncApiFields() { }
}
