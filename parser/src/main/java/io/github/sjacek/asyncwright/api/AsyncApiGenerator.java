package io.github.sjacek.asyncwright.api;

import java.util.List;

/**
 * Runs after successful built-in and project validation; return a non-null list of diagnostics.
 * An error stops later generators. Implementations loaded by name need a public no-argument constructor.
 */
@FunctionalInterface
public interface AsyncApiGenerator {
    /**
     * Generates output from one validated document.
     * @param context invocation-local model, immutable parameters and host services
     * @return non-null diagnostics; an empty list indicates no findings
     * @throws Exception if execution fails; the host preserves the cause separately from validation findings
     */
    List<Diagnostic> generate(GeneratorContext context) throws Exception;
}
