package io.github.sjacek.asyncwright.api;

import java.util.Arrays;

/** Supported Avro schema format, independent of the version of the validating Java library. */
public final class Avro19 {
    /** Schema language version accepted by the profile. */
    public static final String VERSION = "1.9.0";
    /** Default Gradle schema input pattern. */
    public static final String FILE_PATTERN = "**/*.avsc";

    /** Exact Multi Format Schema Object media types; no unversioned or newer aliases are implied. */
    public enum Format {
        /** Avro without a serialization suffix. */
        DEFAULT("application/vnd.apache.avro"),
        /** JSON notation. */
        JSON("application/vnd.apache.avro+json"),
        /** YAML notation within the AsyncAPI document. External .avsc files remain JSON. */
        YAML("application/vnd.apache.avro+yaml");

        private final String mediaType;
        Format(String prefix) { mediaType = prefix + ";version=" + VERSION; }
        /**
         * Returns the complete, versioned wire value.
         * @return supported media type
         */
        public String mediaType() { return mediaType; }
    }

    private Avro19() { }
    /**
     * Checks the exact supported media types.
     * Matching does not trim, normalize case or infer a version; null is unsupported.
     * @param value wire value
     * @return whether supported
     */
    public static boolean supports(String value) {
        return Arrays.stream(Format.values()).anyMatch(format -> format.mediaType().equals(value));
    }
}
