package io.github.sjacek.asyncwright.api;

import java.util.List;
import java.util.Collection;
import java.util.Comparator;

/**
 * A validation finding with a portable source location. Extensions must supply non-blank IDs,
 * non-null fields, relative file names, positive coordinates and document JSON Pointers.
 *
 * @param ruleId stable built-in or project rule identifier
 * @param severity whether the finding prevents generation
 * @param message human-readable explanation
 * @param file project-relative file name using forward slashes
 * @param line one-based line number
 * @param column one-based column number
 * @param pointer document JSON Pointer; empty for the root
 * @param related reference usage locations, copied on construction
 */
public record Diagnostic(String ruleId, Severity severity, String message, String file,
                         int line, int column, String pointer, List<SourceLocation> related) {
    /**
     * Copies related locations; field validity is checked when an extension returns diagnostics.
     * @param ruleId stable built-in or project rule identifier
     * @param severity whether the finding prevents generation
     * @param message human-readable explanation
     * @param file project-relative file name using forward slashes
     * @param line one-based line number
     * @param column one-based column number
     * @param pointer document JSON Pointer; empty for the root
     * @param related reference usage locations to copy
     */
    public Diagnostic {
        related = List.copyOf(related);
    }

    /**
     * Creates a finding at an existing source location.
     * @param ruleId stable rule identifier
     * @param severity finding severity
     * @param message explanation
     * @param location primary location
     * @param related additional reference usage locations
     * @return finding containing a defensive copy of related locations
     */
    public static Diagnostic at(String ruleId, Severity severity, String message,
                                SourceLocation location, List<SourceLocation> related) {
        return new Diagnostic(ruleId, severity, message, location.file(), location.line(),
                location.column(), location.pointer(), related);
    }

    /**
     * Creates a finding using a built-in identifier.
     * @param rule built-in rule
     * @param severity finding severity
     * @param message explanation
     * @param location primary location
     * @param related additional reference usage locations
     * @return finding using the rule's stable wire identifier
     */
    public static Diagnostic at(BuiltinRuleId rule, Severity severity, String message,
                                SourceLocation location, List<SourceLocation> related) {
        return at(rule.id(), severity, message, location, related);
    }

    /**
     * Deduplicates equal findings and orders every diagnostic field deterministically.
     * @param diagnostics findings to normalize
     * @return immutable, sorted diagnostics
     */
    public static List<Diagnostic> sorted(Collection<Diagnostic> diagnostics) {
        return diagnostics.stream().distinct().sorted(Comparator.comparing(Diagnostic::file)
                .thenComparingInt(Diagnostic::line).thenComparingInt(Diagnostic::column)
                .thenComparing(Diagnostic::ruleId).thenComparing(Diagnostic::message)
                .thenComparing(Diagnostic::pointer).thenComparing(Diagnostic::severity)
                .thenComparing(d -> d.related().toString())).toList();
    }
}
