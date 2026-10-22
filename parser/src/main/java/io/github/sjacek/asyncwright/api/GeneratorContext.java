package io.github.sjacek.asyncwright.api;

/** A generator writes relative paths using the task's output directory. */
public interface GeneratorContext extends ExtensionContext {
    /**
     * Returns the shared output writer for this execution.
     * @return directory-scoped writer; paths must be unique across all generators
     */
    GeneratedFiles output();
}
