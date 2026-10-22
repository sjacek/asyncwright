package io.github.sjacek.asyncwright.api;

import java.util.Map;
import java.util.Objects;

/**
 * The host loads this binary class name using its extension classloader.
 *
 * @param className binary class name, using {@code $} for nested classes
 * @param parameters explicit text parameters, copied on construction
 */
public record ExtensionSpec(String className, Map<String, String> parameters) {
    /**
     * Creates a portable extension declaration.
     * @throws IllegalArgumentException if the class name is blank
     * @throws NullPointerException if the class name, map, a key or a value is null
     */
    public ExtensionSpec {
        Objects.requireNonNull(className, "className");
        if (className.isBlank()) throw new IllegalArgumentException("Expected an extension class name");
        parameters = Map.copyOf(parameters);
    }
}
