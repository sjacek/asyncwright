package io.github.sjacek.asyncwright.gradle;

import io.github.sjacek.asyncwright.api.AsyncApiRule;
import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import javax.inject.Inject;
import java.util.List;
import java.util.regex.Pattern;

public abstract class AsyncApiRules {
    private final ObjectFactory objects;
    @Inject public AsyncApiRules(ObjectFactory objects) {
        this.objects = objects;
        getChannelAddress().convention("");
        getChannelAddressFlags().convention(0);
        getDefaultContentType().convention("");
        getProtocol().convention("");
        getCustomRules().convention(List.of());
    }
    @Input public abstract Property<String> getChannelAddress();
    @Input public abstract Property<Integer> getChannelAddressFlags();
    @Input public abstract Property<String> getDefaultContentType();
    @Input public abstract Property<String> getProtocol();
    @Nested public abstract ListProperty<ExtensionOptions> getCustomRules();
    @SuppressWarnings("unused") // Public DSL overload invoked by Gradle build scripts.
    public void channelAddress(String expression) { channelAddress(Pattern.compile(expression)); }
    public void channelAddress(Pattern expression) {
        getChannelAddress().set(expression.pattern());
        getChannelAddressFlags().set(expression.flags());
    }
    @SuppressWarnings("unused") // Public DSL entry point invoked by Gradle build scripts.
    public void requireDefaultContentType(String value) { getDefaultContentType().set(value); }
    @SuppressWarnings("unused") // Public DSL entry point invoked by Gradle build scripts.
    public void requireProtocol(String value) { getProtocol().set(value); }
    public void rule(String className, Action<? super ExtensionOptions> configure) {
        ExtensionOptions options = objects.newInstance(ExtensionOptions.class);
        options.getClassName().set(className);
        configure.execute(options);
        getCustomRules().add(options);
    }
    @SuppressWarnings("unused") // Public DSL overload invoked by Gradle build scripts.
    public void rule(String className) { rule(className, ignored -> { }); }

    /**
     * Convenience for rule types already available to the build script. Only the binary name is retained;
     * the rule JAR must also be available to the worker (normally through asyncwrightExtensions).
     * Use the String overload for extensions built later by a subproject or included build.
     */
    public void rule(Class<? extends AsyncApiRule> ruleType, Action<? super ExtensionOptions> configure) {
        rule(ruleType.getName(), configure);
    }
    @SuppressWarnings("unused") // Public DSL overload invoked by Gradle build scripts.
    public void rule(Class<? extends AsyncApiRule> ruleType) { rule(ruleType, ignored -> { }); }
}
