package io.github.sjacek.asyncwright.api;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Severity used by validation gates and serialized reports.
 */
public enum Severity {
    /**
     * A finding that prevents generation.
     */
    ERROR,
    /** A finding that permits generation. */
    WARNING;

    /**
     * Returns the stable lowercase JSON value.
     * @return {@code error} or {@code warning}
     */
    @JsonValue
    public String value() { return name().toLowerCase(Locale.ROOT); }
}
