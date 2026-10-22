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

/** Base for project generator tasks. Configure a class name; generation always executes inside the worker. */
public abstract class AsyncApiModelTask extends AsyncApiTask {
    private final Provider<Directory> validatedOutputDirectory;
    public AsyncApiModelTask() {
        getParameters().convention(java.util.Map.of());
        var build = getBuildDirectory();
        var input = getFile();
        // Gradle reads declared outputs before cache lookup. An action-only guard is bypassed on FROM-CACHE.
        validatedOutputDirectory = getOutputDirectory().map(directory -> {
            validateGeneratorOutput(directory.getAsFile(), build.get().getAsFile(), input.get().getAsFile());
            return directory;
        });
    }
    @Input public abstract Property<String> getGeneratorClass();
    @Input public abstract MapProperty<String, String> getParameters();
    @Internal public abstract DirectoryProperty getOutputDirectory();
    @OutputDirectory public Provider<Directory> getValidatedOutputDirectory() { return validatedOutputDirectory; }
    @TaskAction public void generate() {
        submit(null, null, getValidatedOutputDirectory().get().getAsFile(), getGeneratorClass().get(), getParameters().get());
    }
}
