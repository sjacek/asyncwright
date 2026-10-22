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

/**
 * Project validation policy. Empty strings disable individual declarative requirements.
 * All properties are task inputs; custom rules run after built-in validation succeeds.
 */
public abstract class AsyncApiRules {
    private final ObjectFactory objects;
    /**
     * Creates managed rule options with all declarative requirements disabled.
     * @param objects Gradle factory for nested extension declarations
     */
    @Inject public AsyncApiRules(ObjectFactory objects) {
        this.objects = objects;
        getChannelAddress().convention("");
        getChannelAddressFlags().convention(0);
        getDefaultContentType().convention("");
        getProtocol().convention("");
        getCustomRules().convention(List.of());
    }
    /**
     * Returns the full-match channel address expression.
     * @return regex source; empty disables the requirement
     */
    @Input public abstract Property<String> getChannelAddress();
    /**
     * Returns flags serialized with the channel address expression.
     * @return bitwise combination of {@link Pattern} flags; defaults to zero
     */
    @Input public abstract Property<Integer> getChannelAddressFlags();
    /**
     * Returns the required root default content type.
     * @return exact required value; empty disables the requirement
     */
    @Input public abstract Property<String> getDefaultContentType();
    /**
     * Returns the protocol required of every declared root server.
     * @return exact required protocol; empty disables the requirement; no server is required to exist
     */
    @Input public abstract Property<String> getProtocol();
    /**
     * Returns ordered declarations of additional rules.
     * @return rule classes and their explicit parameters
     */
    @Nested public abstract ListProperty<ExtensionOptions> getCustomRules();
    /**
     * Requires each channel address to match the entire expression.
     * @param expression Java regular expression
     * @throws java.util.regex.PatternSyntaxException if the expression is invalid
     */
    @SuppressWarnings("unused") // Public DSL overload invoked by Gradle build scripts.
    public void channelAddress(String expression) { channelAddress(Pattern.compile(expression)); }
    /**
     * Stores a compiled expression as its source and flags for worker isolation and caching.
     * @param expression full-match expression, including flags
     */
    public void channelAddress(Pattern expression) {
        getChannelAddress().set(expression.pattern());
        getChannelAddressFlags().set(expression.flags());
    }
    /**
     * Requires the document root to declare an exact content type.
     * @param value required content type, or empty to disable the rule
     */
    @SuppressWarnings("unused") // Public DSL entry point invoked by Gradle build scripts.
    public void requireDefaultContentType(String value) { getDefaultContentType().set(value); }
    /**
     * Requires every declared root server to use the given protocol.
     * @param value required protocol, or empty to disable the rule
     */
    @SuppressWarnings("unused") // Public DSL entry point invoked by Gradle build scripts.
    public void requireProtocol(String value) { getProtocol().set(value); }
    /**
     * Appends a rule whose class may be supplied later by an extension dependency.
     * @param className binary Java name of a public {@code AsyncApiRule} with a public no-argument constructor
     * @param configure action setting explicit text parameters
     */
    public void rule(String className, Action<? super ExtensionOptions> configure) {
        ExtensionOptions options = objects.newInstance(ExtensionOptions.class);
        options.getClassName().set(className);
        configure.execute(options);
        getCustomRules().add(options);
    }
    /**
     * Appends a rule with no parameters.
     * @param className binary name resolved from the worker extension classpath
     */
    @SuppressWarnings("unused") // Public DSL overload invoked by Gradle build scripts.
    public void rule(String className) { rule(className, ignored -> { }); }

    /**
     * Convenience for rule types already available to the build script. Only the binary name is retained;
     * the rule JAR must also be available to the worker (normally through asyncwrightExtensions).
     * Use the String overload for extensions built later by a subproject or included build.
     *
     *
     * @param ruleType rule class already visible to the build script
     * @param configure action setting explicit text parameters
     */
    public void rule(Class<? extends AsyncApiRule> ruleType, Action<? super ExtensionOptions> configure) {
        rule(ruleType.getName(), configure);
    }
    /**
     * Appends a build-script-visible rule with no parameters. Its JAR must also be available to the worker.
     * @param ruleType rule class whose binary name is retained
     */
    @SuppressWarnings("unused") // Public DSL overload invoked by Gradle build scripts.
    public void rule(Class<? extends AsyncApiRule> ruleType) { rule(ruleType, ignored -> { }); }
}
