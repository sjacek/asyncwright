package io.github.sjacek.asyncwright.api;

import java.io.Serial;

/** An extension or classpath failure, distinct from a contract validation diagnostic. */
public final class ExtensionExecutionException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Identifies the callback that failed.
     */
    public enum Kind {
        /** Validation callback. */
        RULE,
        /** Generation callback. */
        GENERATOR
    }
    /**
     * Extension identity retained with the failure.
     * @serial Binary name of the failing extension.
     */
    private final String extensionClass;
    /**
     * Callback identity retained with the failure.
     * @serial Callback category of the failed extension.
     */
    private final Kind kind;

    /**
     * Wraps a loading, construction or callback failure without replacing its cause.
     * @param extensionClass binary Java name of the failing extension
     * @param kind callback category
     * @param cause original failure
     */
    public ExtensionExecutionException(String extensionClass, Kind kind, Throwable cause) {
        super("Failed to execute " + kind.name().toLowerCase(java.util.Locale.ROOT) + " " + extensionClass, cause);
        this.extensionClass = extensionClass;
        this.kind = kind;
    }

    /**
     * Returns the failing extension identity.
     * @return binary Java class name
     */
    public String extensionClass() { return extensionClass; }
    /**
     * Returns the failing callback category.
     * @return rule or generator
     */
    public Kind kind() { return kind; }
}
