package io.github.sjacek.asyncwright.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sjacek.asyncwright.api.Diagnostic;
import io.github.sjacek.asyncwright.api.Severity;

import java.util.List;
import java.util.Optional;

/**
 * Parser outcome after syntax, capability, structural, reference and semantic checks.
 * A successful parser invocation always includes a typed document.
 *
 * @param document validated document, absent when an error prevents model creation
 * @param diagnostics findings normalized into an immutable, sorted, deduplicated list
 */
public record ParseResult(Optional<AsyncApiDocument> document, List<Diagnostic> diagnostics) {
    /**
     * Normalizes diagnostic order without discarding warnings.
     * @param document validated document, absent when an error prevents model creation
     * @param diagnostics parser findings to normalize
     */
    public ParseResult {
        diagnostics = Diagnostic.sorted(diagnostics);
    }

    /**
     * Tests diagnostics for errors; warning-only input is successful.
     * @return whether diagnostics contain no errors
     */
    public boolean successful() { return diagnostics.stream().noneMatch(d -> d.severity() == Severity.ERROR); }

    /**
     * Serializes stable, project-relative diagnostics without embedding the mutable model.
     * @return pretty-printed JSON array
     * @throws JsonProcessingException if a finding cannot be serialized
     */
    public String diagnosticsJson() throws JsonProcessingException {
        return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(diagnostics);
    }
}
