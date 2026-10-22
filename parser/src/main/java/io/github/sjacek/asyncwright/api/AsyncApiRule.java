package io.github.sjacek.asyncwright.api;

import java.util.List;

/**
 * Runs after successful built-in parsing/validation. Return a non-null list of diagnostics.
 * All configured rules run; any error prevents generation. Implementations loaded by name need a public no-argument constructor.
 */
@FunctionalInterface
public interface AsyncApiRule {
    List<Diagnostic> validate(ExtensionContext context) throws Exception;
}
