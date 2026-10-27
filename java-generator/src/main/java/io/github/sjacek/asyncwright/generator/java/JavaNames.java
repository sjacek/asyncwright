package io.github.sjacek.asyncwright.generator.java;

import javax.lang.model.SourceVersion;
import java.beans.Introspector;
import java.util.Set;

/** Java source safety; no input-derived name is inserted into a path or source without validation. */
final class JavaNames {
    // Contextual keywords may be legal identifiers in some positions, but not DTO type names.
    private static final Set<String> RESTRICTED_TYPES = Set.of("var", "yield", "record", "sealed", "permits");
    // These simple names are used by generated fields/imports; shadowing would change their types.
    private static final Set<String> NATIVE_TYPES = Set.of("String", "Object", "Integer", "Long", "Double",
            "Boolean", "UUID", "LocalDate", "List", "Set", "Map");
    private JavaNames() { }

    static boolean identifier(String value) {
        return !value.isEmpty() && SourceVersion.isIdentifier(value)
                && !SourceVersion.isKeyword(value, SourceVersion.RELEASE_17);
    }

    static boolean typeName(String value) {
        return identifier(value) && !RESTRICTED_TYPES.contains(value) && !NATIVE_TYPES.contains(value);
    }

    /** A getter must decode to the original wire name; do not silently change uRL into URL. */
    static boolean property(String value) {
        return identifier(value) && Introspector.decapitalize(capitalized(value)).equals(value);
    }

    static boolean packageName(String value) {
        for (String part : value.split("\\.", -1)) if (!identifier(part)) return false;
        return !value.isEmpty();
    }

    static String capitalized(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /** Escape a structural pointer token; URI decoding is the resolver's separate responsibility. */
    static String token(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    /** Escapes a Java string body, using octal controls to avoid Unicode-escape preprocessing. */
    static String literal(String value) {
        StringBuilder escaped = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 32 || c == 127) escaped.append(String.format("\\%03o", (int) c));
                    else escaped.append(c);
                }
            }
        }
        return escaped.toString();
    }
}
