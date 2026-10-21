package io.github.sjacek.asyncwright.api;

import java.util.List;

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
}
