package io.github.sjacek.asyncwright.generator.java;

import io.github.sjacek.asyncwright.api.AsyncApiGenerator;
import io.github.sjacek.asyncwright.api.Diagnostic;
import io.github.sjacek.asyncwright.api.GeneratorContext;
import io.github.sjacek.asyncwright.api.Severity;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates Java beans and string enums from message payload schemas using the public SPI.
 * References are followed through the host resolver; neither the document nor its model is expanded.
 * Headers and unused component schemas are not DTO roots.
 * <p>
 * This backend supplies transport types, not runtime schema validation. Constraints such as patterns
 * and minima remain in the contract. Unsupported structural schemas produce located diagnostics
 * before any output is written. Java names are checked rather than silently renamed, because a
 * renamed bean property would change its unannotated JSON boundary.
 * <p>
 * Configure {@link #PACKAGE_NAME} and, optionally, {@link #COLLECTION_TYPE} through
 * {@link GeneratorContext#parameters()}. The collection values are {@code List} and
 * {@code Set}, with {@code List} as the default; {@code uniqueItems: true} always uses
 * {@code Set}. Unknown parameters are execution errors rather than ignored hints.
 * <p>
 * Instances hold no invocation state. Planning errors are returned as diagnostics, while
 * configuration and I/O exceptions propagate to the host. The Gradle task supplies output
 * staging and cleanup; direct SPI callers must provide their own rollback after an I/O failure.
 */
public final class JavaDtoGenerator implements AsyncApiGenerator {
    /** Required Java package parameter; every dot-separated segment must be a Java 17 identifier. */
    public static final String PACKAGE_NAME = "packageName";
    /** Optional, case-sensitive {@code List}/{@code Set} selection; uniqueItems always selects Set. */
    public static final String COLLECTION_TYPE = "collectionType";

    /** Creates a stateless generator suitable for isolated worker loading. */
    public JavaDtoGenerator() { }

    /**
     * Plans the complete type graph and writes deterministic UTF-8/LF Java sources.
     * @param context validated document, explicit parameters and directory-scoped output
     * @return no findings on success, or a located generation-profile error
     * @throws IllegalArgumentException if a parameter is absent, unknown or invalid
     * @throws Exception if output fails; the host preserves the underlying infrastructure failure
     */
    @Override public List<Diagnostic> generate(GeneratorContext context) throws Exception {
        Settings settings = Settings.from(context.parameters());
        final List<JavaSource.File> files;
        try {
            files = new SchemaModels(context.document(), settings).compile();
        } catch (SchemaModels.Failure failure) {
            return List.of(context.diagnostic(failure.ruleId, Severity.ERROR, failure.getMessage(), failure.pointer));
        }
        // Planning all models first prevents a later unsupported property from leaving partial output.
        for (JavaSource.File file : files) context.output().writeUtf8(file.path(), file.contents());
        return List.of();
    }

    /** Validates explicit host parameters once, before examining any document or writing a file. */
    record Settings(String packageName, CollectionType collectionType) {
        static Settings from(Map<String, String> parameters) {
            for (String name : parameters.keySet()) {
                if (!Set.of(PACKAGE_NAME, COLLECTION_TYPE).contains(name))
                    throw new IllegalArgumentException("Unknown Java DTO parameter: " + name);
            }
            String packageName = parameters.get(PACKAGE_NAME);
            if (packageName == null || !JavaNames.packageName(packageName))
                throw new IllegalArgumentException("packageName must be a non-empty Java package");
            String collection = parameters.getOrDefault(COLLECTION_TYPE, CollectionType.LIST.spelling);
            return new Settings(packageName, CollectionType.parse(collection));
        }
    }

    /** Internal policy enumeration; spelling is the existing text-parameter representation in the SPI. */
    enum CollectionType {
        LIST("List"), SET("Set");
        final String spelling;
        CollectionType(String spelling) { this.spelling = spelling; }
        static CollectionType parse(String value) {
            for (CollectionType type : values()) if (type.spelling.equals(value)) return type;
            throw new IllegalArgumentException("collectionType must be List or Set");
        }
    }
}
