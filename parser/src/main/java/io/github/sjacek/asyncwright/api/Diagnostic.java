package io.github.sjacek.asyncwright.api;

import java.util.List;
import java.util.Collection;
import java.util.Comparator;

public record Diagnostic(String ruleId, Severity severity, String message, String file,
                         int line, int column, String pointer, List<SourceLocation> related) {
    public Diagnostic {
        related = List.copyOf(related);
    }

    public static Diagnostic at(String ruleId, Severity severity, String message,
                                SourceLocation location, List<SourceLocation> related) {
        return new Diagnostic(ruleId, severity, message, location.file(), location.line(),
                location.column(), location.pointer(), related);
    }

    public static List<Diagnostic> sorted(Collection<Diagnostic> diagnostics) {
        return diagnostics.stream().distinct().sorted(Comparator.comparing(Diagnostic::file)
                .thenComparingInt(Diagnostic::line).thenComparingInt(Diagnostic::column)
                .thenComparing(Diagnostic::ruleId).thenComparing(Diagnostic::message)
                .thenComparing(Diagnostic::pointer).thenComparing(Diagnostic::severity)
                .thenComparing(d -> d.related().toString())).toList();
    }
}
