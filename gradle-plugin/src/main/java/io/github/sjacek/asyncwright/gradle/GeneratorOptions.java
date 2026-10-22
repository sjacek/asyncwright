package io.github.sjacek.asyncwright.gradle;

import org.gradle.api.Named;
import org.jspecify.annotations.NullMarked;
import javax.inject.Inject;

/** Each generator receives its own task-owned output directory. */
@NullMarked
public abstract class GeneratorOptions extends ExtensionOptions implements Named {
    private final String name;
    /**
     * Creates a named generator declaration.
     * @param name container identity used in the task suffix and output directory
     */
    @Inject public GeneratorOptions(String name) { this.name = name; }
    @Override public String getName() { return name; }
}
