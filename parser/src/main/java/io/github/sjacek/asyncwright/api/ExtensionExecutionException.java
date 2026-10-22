package io.github.sjacek.asyncwright.api;

import java.io.Serial;

/** An extension or classpath failure, distinct from a contract validation diagnostic. */
public final class ExtensionExecutionException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    public enum Kind { RULE, GENERATOR }
    private final String extensionClass;
    private final Kind kind;

    public ExtensionExecutionException(String extensionClass, Kind kind, Throwable cause) {
        super("Failed to execute " + kind.name().toLowerCase(java.util.Locale.ROOT) + " " + extensionClass, cause);
        this.extensionClass = extensionClass;
        this.kind = kind;
    }

    public String extensionClass() { return extensionClass; }
    public Kind kind() { return kind; }
}
