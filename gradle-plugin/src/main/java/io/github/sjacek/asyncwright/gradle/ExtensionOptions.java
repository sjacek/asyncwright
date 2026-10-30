package io.github.sjacek.asyncwright.gradle;

import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;

/** A binary class name and explicit text parameters; no build-script closure enters a worker. */
public abstract class ExtensionOptions {
    /**
     * Returns the extension identity retained across the worker boundary.
     * @return binary Java name of a public extension class with a public no-argument constructor
     */
    @Input
    public abstract Property<String> getClassName();

    /**
     * Returns explicitly declared, cache-tracked text parameters.
     * @return parameter map, empty by default
     */
    @Input
    public abstract MapProperty<String, String> getParameters();

    /**
     * Creates options with no parameters and no default implementation class.
     */
    public ExtensionOptions() { getParameters().convention(java.util.Map.of()); }
}
