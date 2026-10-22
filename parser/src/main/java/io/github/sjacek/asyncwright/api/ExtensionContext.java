package io.github.sjacek.asyncwright.api;

import java.util.Map;

/** One invocation's document view and immutable, explicitly supplied text parameters. */
public interface ExtensionContext {
    AsyncApiDocumentView document();
    Map<String, String> parameters();

    default String requireParameter(String name) {
        String value = parameters().get(name);
        if (value == null) throw new IllegalArgumentException("Missing extension parameter: " + name);
        return value;
    }

    /** Definition diagnostics automatically include reference usage locations. */
    default Diagnostic diagnostic(String ruleId, Severity severity, String message, String pointer) {
        return Diagnostic.at(ruleId, severity, message, document().location(pointer), document().references().related(pointer));
    }
}
