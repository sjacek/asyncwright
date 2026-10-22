package io.github.sjacek.asyncwright.api;

import java.util.List;

/**
 * Runs after successful built-in and project validation; return a non-null list of diagnostics.
 * An error stops later generators. Implementations loaded by name need a public no-argument constructor.
 */
@FunctionalInterface
public interface AsyncApiGenerator {
    List<Diagnostic> generate(GeneratorContext context) throws Exception;
}
