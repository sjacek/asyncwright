package io.github.sjacek.asyncwright.api;

/** Constructs document-local JSON Pointer tokens, independently of URI fragment encoding. */
public final class JsonPointers {
    private JsonPointers() { }
    public static String escape(String token) { return token.replace("~", "~0").replace("/", "~1"); }
    public static String append(String pointer, String token) { return pointer + "/" + escape(token); }
}
