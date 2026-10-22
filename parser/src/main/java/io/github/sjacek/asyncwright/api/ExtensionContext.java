package io.github.sjacek.asyncwright.api;

import java.util.Map;

/** One invocation's document view and immutable, explicitly supplied text parameters. */
public interface ExtensionContext {
    /**
     * Returns document access for this invocation.
     * @return a view with an isolated mutable typed model
     */
    AsyncApiDocumentView document();
    /**
     * Returns explicitly configured parameters; environment variables are not added.
     * @return immutable text parameter map
     */
    Map<String, String> parameters();

    /**
     * Reads a required parameter without trimming or interpreting its value.
     * @param name parameter key
     * @return supplied value, including an explicitly supplied empty string
     * @throws IllegalArgumentException if the key is absent
     */
    default String requireParameter(String name) {
        String value = parameters().get(name);
        if (value == null) throw new IllegalArgumentException("Missing extension parameter: " + name);
        return value;
    }

    /**
     * Creates a finding at the affected definition and includes reference usage locations.
     * @param ruleId stable project or built-in identifier
     * @param severity finding severity
     * @param message explanation
     * @param pointer source JSON Pointer, or empty for the root
     * @return located finding with related reference usages
     */
    default Diagnostic diagnostic(String ruleId, Severity severity, String message, String pointer) {
        return Diagnostic.at(ruleId, severity, message, document().location(pointer), document().references().related(pointer));
    }

    /**
     * Creates a located finding with a built-in identifier.
     * @param rule built-in rule
     * @param severity finding severity
     * @param message explanation
     * @param pointer source JSON Pointer, or empty for the root
     * @return located finding with related reference usages
     */
    default Diagnostic diagnostic(BuiltinRuleId rule, Severity severity, String message, String pointer) {
        return diagnostic(rule.id(), severity, message, pointer);
    }
}
