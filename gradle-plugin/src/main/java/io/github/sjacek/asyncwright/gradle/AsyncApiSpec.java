package io.github.sjacek.asyncwright.gradle;

import org.gradle.api.Action;
import org.gradle.api.Named;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.file.ConfigurableFileCollection;
import io.github.sjacek.asyncwright.api.Avro19;
import org.gradle.api.model.ObjectFactory;
import org.jspecify.annotations.NullMarked;
import javax.inject.Inject;

/**
 * One named input document and its validation and generation policy.
 * Names must match {@code [a-zA-Z][a-zA-Z0-9]*}; their capitalized task suffixes must be unique.
 */
@NullMarked
public abstract class AsyncApiSpec implements Named {
    private final String name;
    private final AsyncApiRules rules;
    private final NamedDomainObjectContainer<GeneratorOptions> generators;
    /**
     * Creates a specification through the Gradle object factory.
     * @param name container identity used in task and output names
     * @param objects factory for nested rule and generator options
     */
    @Inject public AsyncApiSpec(String name, ObjectFactory objects) {
        this.name = name;
        rules = objects.newInstance(AsyncApiRules.class);
        var schemas = objects.fileCollection().from(getFile().map(file -> file.getAsFile().getParentFile()))
                .getAsFileTree().matching(pattern -> pattern.include(Avro19.FILE_PATTERN));
        getSchemaFiles().from(schemas);
        generators = objects.domainObjectContainer(GeneratorOptions.class,
                id -> objects.newInstance(GeneratorOptions.class, id));
    }
    @Override public String getName() { return name; }
    /**
     * Returns the JSON or YAML document to validate.
     * @return required input file located inside the project directory
     */
    public abstract RegularFileProperty getFile();
    /**
     * Returns declared local Avro file inputs. Defaults lazily to .avsc files below the document directory.
     * Use setFrom(files/fileTree) to replace the default allowlist, or from(...) to extend it.
     * Task-backed collections retain their producer dependencies. Unreferenced declared files
     * still affect task fingerprints; named Avro types are never shared across these files.
     * @return configurable files/file trees; every referenced file must be inside the project
     */
    public abstract ConfigurableFileCollection getSchemaFiles();
    /**
     * Returns this document's validation policy.
     * @return managed rule options shared by its validation and generation tasks
     */
    public AsyncApiRules getRules() { return rules; }
    /**
     * Configures validation policy.
     * @param action configuration action executed during build configuration
     */
    public void rules(Action<? super AsyncApiRules> action) { action.execute(rules); }
    /**
     * Returns named generators, each producing a separate task and output directory.
     * @return generator declarations; generator names follow the specification naming pattern
     */
    public NamedDomainObjectContainer<GeneratorOptions> getGenerators() { return generators; }
    /**
     * Configures named generators.
     * @param action configuration action executed during build configuration
     */
    @SuppressWarnings("unused") // Public DSL entry point invoked by Gradle build scripts.
    public void generators(Action<? super NamedDomainObjectContainer<GeneratorOptions>> action) { action.execute(generators); }
}
