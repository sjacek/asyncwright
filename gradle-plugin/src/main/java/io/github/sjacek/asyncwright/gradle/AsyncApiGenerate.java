package io.github.sjacek.asyncwright.gradle;

import org.gradle.api.tasks.CacheableTask;

/** Cacheable generator registered by the DSL. Generators must use only declared inputs. */
@CacheableTask
public abstract class AsyncApiGenerate extends AsyncApiModelTask {
    /**
     * Creates a cacheable DSL generator task with all inputs declared by its base type.
     */
    public AsyncApiGenerate() { }
}
