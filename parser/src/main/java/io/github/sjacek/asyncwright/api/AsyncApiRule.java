package io.github.sjacek.asyncwright.api;

import java.util.List;

/**
 * Runs after successful built-in parsing/validation. Return a non-null list of diagnostics.
 * All configured rules run; any error prevents generation. Implementations loaded by name need a public no-argument constructor.
 */
@FunctionalInterface
public interface AsyncApiRule {
    /**
     * Validates one validated document.
     * @param context invocation-local model, immutable parameters and host services
     * @return non-null diagnostics; an empty list indicates no findings
     * @throws Exception if execution fails; the host preserves the cause separately from validation findings
     */
    List<Diagnostic> validate(ExtensionContext context) throws Exception;
}
