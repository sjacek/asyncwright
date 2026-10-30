package io.github.sjacek.asyncwright.gradle;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.model.ObjectFactory;
import javax.inject.Inject;

/**
 * Root {@code asyncwright} DSL extension. Adding a specification registers its validation task;
 * adding a generator registers a generation task. The aggregate validation task is wired into {@code check}.
 */
public class AsyncwrightExtension {
    private final NamedDomainObjectContainer<AsyncApiSpec> specs;
    /**
     * Creates the specification container.
     * @param objects Gradle factory for managed specification declarations
     */
    @Inject
    public AsyncwrightExtension(ObjectFactory objects) {
        specs = objects.domainObjectContainer(AsyncApiSpec.class, name -> objects.newInstance(AsyncApiSpec.class, name));
    }

    /**
     * Returns input document declarations.
     * @return named specifications, initially empty
     */
    public NamedDomainObjectContainer<AsyncApiSpec> getSpecs() { return specs; }

    /**
     * Configures input document declarations.
     * @param configure action executed during build configuration
     */
    @SuppressWarnings("unused") // Public DSL entry point invoked by Gradle build scripts.
    public void specs(Action<? super NamedDomainObjectContainer<AsyncApiSpec>> configure) { configure.execute(specs); }
}
