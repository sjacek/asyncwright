package io.github.sjacek.asyncwright.api;

/** A generator writes relative paths using the task's output directory. */
public interface GeneratorContext extends ExtensionContext {
    GeneratedFiles output();
}
