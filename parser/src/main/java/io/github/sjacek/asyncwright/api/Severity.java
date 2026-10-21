package io.github.sjacek.asyncwright.api;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum Severity {
    ERROR, WARNING;

    @JsonValue
    public String value() { return name().toLowerCase(Locale.ROOT); }
}
