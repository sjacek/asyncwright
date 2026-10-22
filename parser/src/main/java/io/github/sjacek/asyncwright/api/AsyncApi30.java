package io.github.sjacek.asyncwright.api;

import java.util.Arrays;
import java.util.Set;

/** Pinned specification metadata shared by the parser and its hosts. */
public final class AsyncApi30 {
    /**
     * The only AsyncAPI document version accepted by this parser.
     */
    public static final String VERSION = "3.0.0";
    /**
     * Classpath location of the bundled, pinned specification schema.
     */
    public static final String SCHEMA_RESOURCE = "/schemas/asyncapi/" + VERSION + ".json";
    /**
     * Canonical identifier of the bundled AsyncAPI document schema; never fetched remotely.
     */
    public static final String SCHEMA_ID = "http://asyncapi.com/definitions/" + VERSION + "/asyncapi.json";
    /**
     * Bundled schema definition adapted to admit boolean schema leaves.
     */
    public static final String ANY_SCHEMA_DEFINITION = "http://asyncapi.com/definitions/" + VERSION + "/anySchema.json";
    private static final Set<String> DIALECTS = Set.of("http://json-schema.org/draft-07/schema",
            "http://json-schema.org/draft-07/schema#", "https://json-schema.org/draft-07/schema",
            "https://json-schema.org/draft-07/schema#");

    /**
     * Supported Multi Format Schema Object media types, including their explicit version.
     */
    public enum SchemaFormat {
        /**
         * AsyncAPI schema media type without a serialization suffix.
         */
        DEFAULT("application/vnd.aai.asyncapi"),
        /**
         * AsyncAPI schema media type with JSON serialization.
         */
        JSON("application/vnd.aai.asyncapi+json"),
        /**
         * AsyncAPI schema media type with YAML serialization.
         */
        YAML("application/vnd.aai.asyncapi+yaml");

        private final String mediaType;
        SchemaFormat(String mediaType) { this.mediaType = mediaType + ";version=" + VERSION; }
        /**
         * Returns the complete media type including the version parameter.
         * @return exact supported wire value
         */
        public String mediaType() { return mediaType; }
    }

    private AsyncApi30() { }
    /**
     * Checks an explicitly supplied schema format without normalization.
     * @param value complete media type, including its version
     * @return whether the format is implemented
     */
    public static boolean supportsSchemaFormat(String value) {
        return Arrays.stream(SchemaFormat.values()).anyMatch(format -> format.mediaType().equals(value));
    }
    /**
     * Checks the supported draft-07 dialect URI spellings.
     * @param value dialect URI, with an optional trailing fragment marker
     * @return whether the URI identifies the supported dialect
     */
    public static boolean supportsSchemaDialect(String value) { return DIALECTS.contains(value); }
}
