package io.github.sjacek.asyncwright.gradle;

import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Base for project generator tasks. Configure a class name; generation always executes inside the worker. */
@DisableCachingByDefault(because = "Project generator subclasses must declare additional inputs before opting into caching.")
public abstract class AsyncApiModelTask extends AsyncApiTask {
    private final Provider<Directory> validatedOutputDirectory;
    /**
     * Initializes empty parameters and an output provider that validates before cache lookup.
     */
    public AsyncApiModelTask() {
        getParameters().convention(java.util.Map.of());
        var build = getBuildDirectory();
        var input = getFile();
        var schemas = getSchemaFiles();
        // Gradle reads declared outputs before cache lookup. An action-only guard is bypassed on FROM-CACHE.
        validatedOutputDirectory = getOutputDirectory().map(directory -> {
            validateGeneratorOutput(directory.getAsFile(), build.get().getAsFile(), input.get().getAsFile());
            // Protect declared Avro inputs before cache restoration as well as worker cleanup.
            for (var schema : schemas) validateGeneratorOutput(directory.getAsFile(), build.get().getAsFile(), schema);
            return directory;
        });
    }

    /**
     * Returns the generator implementation loaded inside the worker.
     * @return required binary name of a public generator with a public no-argument constructor
     */
    @Input
    public abstract Property<String> getGeneratorClass();

    /**
     * Returns explicit generator inputs.
     * @return cache-tracked text parameter map, empty by default
     */
    @Input
    public abstract MapProperty<String, String> getParameters();

    /**
     * Returns the configurable task-owned output destination.
     * @return dedicated directory strictly below the build directory and not containing the input
     */
    @Internal
    public abstract DirectoryProperty getOutputDirectory();

    /**
     * Returns the declared output after checking its boundary, including on cache hits.
     * @return validated output provider
     * @throws IllegalArgumentException when the provider is queried for an unsafe destination
     */
    @OutputDirectory
    public Provider<Directory> getValidatedOutputDirectory() { return validatedOutputDirectory; }

    /**
     * Validates the document and runs its generator in isolation.
     * A worker failure removes output; success synchronizes fresh staging output, removing obsolete files.
     * Invalid output boundaries fail before worker cleanup so protected files remain untouched.
     */
    @TaskAction
    public void generate() {
        submit(null, null, getValidatedOutputDirectory().get().getAsFile(), getGeneratorClass().get(), getParameters().get());
    }
}
