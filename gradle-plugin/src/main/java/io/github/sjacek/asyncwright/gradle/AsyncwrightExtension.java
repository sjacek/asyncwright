package io.github.sjacek.asyncwright.gradle;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.model.ObjectFactory;
import javax.inject.Inject;

public class AsyncwrightExtension {
    private final NamedDomainObjectContainer<AsyncApiSpec> specs;
    @Inject public AsyncwrightExtension(ObjectFactory objects) {
        specs = objects.domainObjectContainer(AsyncApiSpec.class, name -> objects.newInstance(AsyncApiSpec.class, name));
    }
    public NamedDomainObjectContainer<AsyncApiSpec> getSpecs() { return specs; }
    @SuppressWarnings("unused") // Public DSL entry point invoked by Gradle build scripts.
    public void specs(Action<? super NamedDomainObjectContainer<AsyncApiSpec>> configure) { configure.execute(specs); }
}
