package io.github.sjacek.asyncwright.gradle;

import org.gradle.api.Action;
import org.gradle.api.Named;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.jspecify.annotations.NullMarked;
import javax.inject.Inject;

@NullMarked
public abstract class AsyncApiSpec implements Named {
    private final String name;
    private final AsyncApiRules rules;
    private final NamedDomainObjectContainer<GeneratorOptions> generators;
    @Inject public AsyncApiSpec(String name, ObjectFactory objects) {
        this.name = name;
        rules = objects.newInstance(AsyncApiRules.class);
        generators = objects.domainObjectContainer(GeneratorOptions.class,
                id -> objects.newInstance(GeneratorOptions.class, id));
    }
    @Override public String getName() { return name; }
    public abstract RegularFileProperty getFile();
    public AsyncApiRules getRules() { return rules; }
    public void rules(Action<? super AsyncApiRules> action) { action.execute(rules); }
    public NamedDomainObjectContainer<GeneratorOptions> getGenerators() { return generators; }
    @SuppressWarnings("unused") // Public DSL entry point invoked by Gradle build scripts.
    public void generators(Action<? super NamedDomainObjectContainer<GeneratorOptions>> action) { action.execute(generators); }
}
