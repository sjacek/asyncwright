package io.github.sjacek.asyncwright.api;

import java.util.Arrays;
import java.util.Set;

/** Pinned specification metadata shared by the parser and its hosts. */
public final class AsyncApi30 {
    public static final String VERSION = "3.0.0";
    public static final String SCHEMA_RESOURCE = "/schemas/asyncapi/" + VERSION + ".json";
    public static final String SCHEMA_ID = "http://asyncapi.com/definitions/" + VERSION + "/asyncapi.json";
    public static final String ANY_SCHEMA_DEFINITION = "http://asyncapi.com/definitions/" + VERSION + "/anySchema.json";
    private static final Set<String> DIALECTS = Set.of("http://json-schema.org/draft-07/schema",
            "http://json-schema.org/draft-07/schema#", "https://json-schema.org/draft-07/schema",
            "https://json-schema.org/draft-07/schema#");

    public enum SchemaFormat {
        DEFAULT("application/vnd.aai.asyncapi"),
        JSON("application/vnd.aai.asyncapi+json"),
        YAML("application/vnd.aai.asyncapi+yaml");

        private final String mediaType;
        SchemaFormat(String mediaType) { this.mediaType = mediaType + ";version=" + VERSION; }
        public String mediaType() { return mediaType; }
    }

    private AsyncApi30() { }
    public static boolean supportsSchemaFormat(String value) {
        return Arrays.stream(SchemaFormat.values()).anyMatch(format -> format.mediaType().equals(value));
    }
    public static boolean supportsSchemaDialect(String value) { return DIALECTS.contains(value); }
}
