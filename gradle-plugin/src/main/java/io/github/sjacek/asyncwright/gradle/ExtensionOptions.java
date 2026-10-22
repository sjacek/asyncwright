package io.github.sjacek.asyncwright.gradle;

import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;

/** A binary class name and explicit text parameters; no build-script closure enters a worker. */
public abstract class ExtensionOptions {
    @Input public abstract Property<String> getClassName();
    @Input public abstract MapProperty<String, String> getParameters();
    public ExtensionOptions() { getParameters().convention(java.util.Map.of()); }
}
