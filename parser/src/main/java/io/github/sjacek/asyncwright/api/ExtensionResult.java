package io.github.sjacek.asyncwright.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** Combined parser/extension diagnostics and relative paths written during this execution. */
public record ExtensionResult(List<Diagnostic> diagnostics, List<String> generatedFiles) {
    public ExtensionResult {
        diagnostics = Diagnostic.sorted(diagnostics);
        generatedFiles = generatedFiles.stream().distinct().sorted().toList();
    }

    public boolean successful() { return diagnostics.stream().noneMatch(d -> d.severity() == Severity.ERROR); }

    public String diagnosticsJson() throws JsonProcessingException {
        return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(diagnostics);
    }
}
