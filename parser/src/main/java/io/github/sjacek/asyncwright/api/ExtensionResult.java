package io.github.sjacek.asyncwright.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * Combined parser/extension diagnostics and relative paths written during this execution.
 *
 * @param diagnostics parser and extension findings, normalized on construction
 * @param generatedFiles paths written during this execution, relative to the output directory
 */
public record ExtensionResult(List<Diagnostic> diagnostics, List<String> generatedFiles) {
    /**
     * Sorts and deduplicates both result collections into immutable snapshots.
     * @param diagnostics parser and extension findings to normalize
     * @param generatedFiles output-relative paths to normalize
     */
    public ExtensionResult {
        diagnostics = Diagnostic.sorted(diagnostics);
        generatedFiles = generatedFiles.stream().distinct().sorted().toList();
    }

    /**
     * Checks whether any finding prevents generation.
     * @return true for an empty or warning-only diagnostic list
     */
    public boolean successful() { return diagnostics.stream().noneMatch(d -> d.severity() == Severity.ERROR); }

    /**
     * Serializes diagnostics only, in their deterministic order.
     * @return pretty-printed JSON array
     * @throws JsonProcessingException if a finding cannot be serialized
     */
    public String diagnosticsJson() throws JsonProcessingException {
        return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(diagnostics);
    }
}
