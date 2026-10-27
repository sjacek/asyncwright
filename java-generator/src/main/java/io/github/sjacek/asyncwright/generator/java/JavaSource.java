package io.github.sjacek.asyncwright.generator.java;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Stable, annotation-free bean rendering; preserves the existing transport source/API baseline. */
final class JavaSource {
    private static final List<String> NATIVE_ORDER = List.of("java.util.Map", "java.util.List", "java.util.Set",
            "java.time.LocalDate", "java.util.UUID");
    private JavaSource() { }

    /** Pure rendering after planning: no filesystem, resolver or mutable global state is consulted. */
    static File render(SchemaModels.Model model, Map<String, SchemaModels.Model> models, String packageName) {
        Set<String> referenced = new LinkedHashSet<>();
        Set<String> nativeImports = new HashSet<>();
        for (SchemaModels.Field field : model.fields) {
            for (String pointer : field.type().modelPointers()) referenced.add(models.get(pointer).name);
            nativeImports.addAll(field.type().imports());
        }
        // Legacy object imports include Map when a referenced model exposes additional values.
        if (hasMap(model, models, new HashSet<>())) nativeImports.add("java.util.Map");
        String references = referenced.stream().map(name -> "import " + packageName + "." + name + ";")
                .collect(Collectors.joining("\n"));
        String natives = NATIVE_ORDER.stream().filter(nativeImports::contains)
                .map(name -> "import " + name + ";").collect(Collectors.joining("\n"));
        StringBuilder text = new StringBuilder("package ").append(packageName).append(";\n")
                .append(references).append('\n').append(natives).append('\n');
        if (model.enumeration) enumBody(text, model);
        else beanBody(text, model);
        // The compatibility baseline has no terminal newline; retain every source byte deliberately.
        return new File(packageName.replace('.', '/') + "/" + model.name + ".java", text.toString());
    }

    /** Preserves legacy transitive Map imports while terminating on recursive bean graphs. */
    private static boolean hasMap(SchemaModels.Model model, Map<String, SchemaModels.Model> models, Set<String> visited) {
        if (!visited.add(model.pointer)) return false;
        for (SchemaModels.Field field : model.fields) {
            if (field.type().imports().contains("java.util.Map")) return true;
            for (String pointer : field.type().modelPointers()) if (hasMap(models.get(pointer), models, visited)) return true;
        }
        return false;
    }

    private static void beanBody(StringBuilder text, SchemaModels.Model model) {
        text.append("public class ").append(model.name).append(" {\n");
        for (SchemaModels.Field field : model.fields) {
            text.append("  private ");
            if (field.constant() != null) text.append("final ");
            text.append(field.type().text()).append(' ').append(field.name());
            if (field.constant() != null) text.append(" = ").append(field.constant());
            text.append(";\n");
        }
        for (SchemaModels.Field field : model.fields) {
            String type = field.type().text(), name = field.name(), accessor = JavaNames.capitalized(name);
            text.append("\n  public ").append(type).append(" get").append(accessor)
                    .append("() { return this.").append(name).append("; }\n");
            if (field.constant() == null)
                text.append("  public void set").append(accessor).append('(').append(type).append(' ').append(name)
                        .append(") { this.").append(name).append(" = ").append(name).append("; }\n");
        }
        text.append('}');
    }

    /** Java symbols and wire values remain separate; null input is not an implicit enum member. */
    private static void enumBody(StringBuilder text, SchemaModels.Model model) {
        String name = model.name;
        text.append("public enum ").append(name).append(" {\n");
        text.append("  ").append(model.values.entrySet().stream()
                .map(value -> value.getKey() + "((String)\"" + JavaNames.literal(value.getValue()) + "\")")
                .collect(Collectors.joining(", "))).append(";\n\n")
                .append("  private final String value;\n\n")
                .append("  ").append(name).append("(String value) {\n    this.value = value;\n  }\n\n")
                .append("  public String getValue() {\n    return value;\n  }\n\n")
                .append("  public static ").append(name).append(" fromValue(String value) {\n")
                .append("    for (").append(name).append(" e : ").append(name).append(".values()) {\n")
                .append("      if (e.value.equals(value)) {\n        return e;\n      }\n    }\n")
                .append("    throw new IllegalArgumentException(\"Unexpected value '\" + value + \"'\");\n  }\n\n")
                .append("  @Override\n  public String toString() {\n    return String.valueOf(value);\n  }\n}");
    }

    record File(String path, String contents) { }
}
