package io.github.sjacek.asyncwright.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sjacek.asyncwright.api.Diagnostic;
import io.github.sjacek.asyncwright.api.Severity;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public record ParseResult(Optional<AsyncApiDocument> document, List<Diagnostic> diagnostics) {
    public ParseResult {
        diagnostics = diagnostics.stream().distinct().sorted(Comparator.comparing(Diagnostic::file)
                .thenComparingInt(Diagnostic::line).thenComparingInt(Diagnostic::column)
                .thenComparing(Diagnostic::ruleId).thenComparing(Diagnostic::message)).toList();
    }

    public boolean successful() { return diagnostics.stream().noneMatch(d -> d.severity() == Severity.ERROR); }

    /** Stable, project-relative diagnostics only; never serializes the mutable model. */
    public String diagnosticsJson() throws JsonProcessingException {
        return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(diagnostics);
    }
}
