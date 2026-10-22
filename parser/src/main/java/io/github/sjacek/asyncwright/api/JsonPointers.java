package io.github.sjacek.asyncwright.api;

/** Constructs document-local JSON Pointer tokens, independently of URI fragment encoding. */
public final class JsonPointers {
    private JsonPointers() { }
    /**
     * Escapes one decoded property name as an RFC 6901 token; does not percent-encode it.
     * @param token decoded property name, possibly empty
     * @return token with tilde and slash escaped
     */
    public static String escape(String token) { return token.replace("~", "~0").replace("/", "~1"); }
    /**
     * Appends a decoded property name to a document JSON Pointer.
     * @param pointer parent pointer, or empty for the root
     * @param token decoded property name
     * @return pointer to the named child
     */
    public static String append(String pointer, String token) { return pointer + "/" + escape(token); }
}
