package io.github.sjacek.asyncwright.api;

import java.util.Map;
import java.util.Objects;

/** The host loads this binary class name using its extension classloader. */
public record ExtensionSpec(String className, Map<String, String> parameters) {
    public ExtensionSpec {
        Objects.requireNonNull(className, "className");
        if (className.isBlank()) throw new IllegalArgumentException("Expected an extension class name");
        parameters = Map.copyOf(parameters);
    }
}
