package io.github.sjacek.asyncwright.gradle;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.*;

/**
 * Validates one specification and writes sorted, project-relative JSON and text reports.
 * Contract errors leave reports available and fail the task; worker failures remove stale reports.
 */
@CacheableTask
public abstract class AsyncApiValidate extends AsyncApiTask {
    /**
     * Creates a validation task; configure its input and both report destinations before execution.
     */
    public AsyncApiValidate() { }

    /**
     * Returns the JSON diagnostic array destination.
     * @return required report file
     */
    @OutputFile
    public abstract RegularFileProperty getJsonReport();

    /**
     * Returns the human-readable diagnostic report destination.
     * @return required report file; an error-free document produces an empty file
     */
    @OutputFile
    public abstract RegularFileProperty getTextReport();

    /**
     * Runs built-in and project validation in an isolated worker.
     */
    @TaskAction
    public void validate() {
        submit(getJsonReport().get().getAsFile(), getTextReport().get().getAsFile(), null, null, java.util.Map.of());
    }
}
