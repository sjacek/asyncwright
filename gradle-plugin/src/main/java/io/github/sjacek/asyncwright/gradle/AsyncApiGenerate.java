package io.github.sjacek.asyncwright.gradle;

import org.gradle.api.tasks.CacheableTask;

/**
 * Cacheable generator registered by the DSL, including the bundled Java DTO backend.
 * Generators must use only declared inputs; they execute in the base task's isolated worker.
 * <p>Compilation of generated Java is opt-in: register this task's output as a source root
 * and make the Java compile task depend on this task. Registration alone does not make
 * every generator part of the Java lifecycle.
 */
@CacheableTask
public abstract class AsyncApiGenerate extends AsyncApiModelTask {
    /**
     * Creates a cacheable DSL generator task with all inputs declared by its base type.
     */
    public AsyncApiGenerate() { }
}
