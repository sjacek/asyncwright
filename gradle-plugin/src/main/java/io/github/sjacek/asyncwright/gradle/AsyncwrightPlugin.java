package io.github.sjacek.asyncwright.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.api.tasks.TaskProvider;
import java.util.HashSet;
import java.util.Set;

public final class AsyncwrightPlugin implements Plugin<Project> {
    public static final String PLUGIN_ID = "io.github.sjacek.asyncwright";
    public static final String EXTENSION_NAME = "asyncwright";
    public static final String EXTENSIONS_CONFIGURATION_NAME = "asyncwrightExtensions";
    public static final String VALIDATE_TASK_NAME = "asyncApiValidate";
    public static final String GENERATE_TASK_PREFIX = "asyncApiGenerate";
    private static final String REPORTS_DIRECTORY = "reports/asyncapi/";
    private static final String GENERATED_DIRECTORY = "generated/asyncapi/";
    private static final String GENERATION_GROUP = "code generation";
    private static final String NAME_PATTERN = "[a-zA-Z][a-zA-Z0-9]*";

    @Override public void apply(Project project) {
        project.getPluginManager().apply(BasePlugin.class);
        Configuration extensions = project.getConfigurations().create(EXTENSIONS_CONFIGURATION_NAME, configuration -> {
            configuration.setCanBeConsumed(false);
            configuration.setCanBeResolved(true);
            configuration.setDescription("Rule and generator JARs loaded by isolated Asyncwright workers.");
            configuration.attributes(attributes -> {
                attributes.attribute(org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE,
                        project.getObjects().named(org.gradle.api.attributes.Usage.class, org.gradle.api.attributes.Usage.JAVA_RUNTIME));
                attributes.attribute(org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE,
                        project.getObjects().named(org.gradle.api.attributes.Category.class, org.gradle.api.attributes.Category.LIBRARY));
            });
        });
        AsyncwrightExtension extension = project.getExtensions().create(EXTENSION_NAME, AsyncwrightExtension.class);
        TaskProvider<org.gradle.api.Task> aggregate = project.getTasks().register(VALIDATE_TASK_NAME, task -> {
            task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
            task.setDescription("Validate every configured AsyncAPI specification.");
        });
        project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME).configure(task -> task.dependsOn(aggregate));
        Set<String> names = new HashSet<>();
        extension.getSpecs().all(spec -> {
            String suffix = suffix(spec.getName());
            if (!names.add(suffix)) throw new IllegalArgumentException("Colliding AsyncAPI task name: " + spec.getName());
            TaskProvider<AsyncApiValidate> validate = project.getTasks().register(VALIDATE_TASK_NAME + suffix,
                    AsyncApiValidate.class, task -> {
                        configure(project, task, spec, extensions);
                        task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
                        task.setDescription("Validate AsyncAPI specification " + spec.getName() + ".");
                        task.getJsonReport().convention(project.getLayout().getBuildDirectory().file(REPORTS_DIRECTORY + spec.getName() + ".json"));
                        task.getTextReport().convention(project.getLayout().getBuildDirectory().file(REPORTS_DIRECTORY + spec.getName() + ".txt"));
                    });
            aggregate.configure(task -> task.dependsOn(validate));
            spec.getGenerators().all(generator -> project.getTasks().register(GENERATE_TASK_PREFIX + suffix + suffix(generator.getName()),
                    AsyncApiGenerate.class, task -> {
                        configure(project, task, spec, extensions);
                        task.getGeneratorClass().set(generator.getClassName());
                        task.getParameters().set(generator.getParameters());
                        task.getOutputDirectory().convention(project.getLayout().getBuildDirectory()
                                .dir(GENERATED_DIRECTORY + spec.getName() + "/" + generator.getName()));
                        task.setGroup(GENERATION_GROUP);
                        task.setDescription("Generate " + generator.getName() + " from " + spec.getName() + ".");
                    }));
        });
    }
    private static void configure(Project project, AsyncApiTask task, AsyncApiSpec spec, Configuration extensions) {
        task.getFile().set(spec.getFile());
        task.getProjectDirectory().set(project.getLayout().getProjectDirectory());
        var root = project.getLayout().getProjectDirectory().getAsFile().toPath();
        // Capture a Path, never Project, in the provider used to fingerprint the relative source name.
        task.getSourcePath().set(spec.getFile().map(file -> {
            var path = file.getAsFile().toPath();
            if (!path.startsWith(root)) throw new IllegalArgumentException("AsyncAPI document must be inside the project directory");
            return root.relativize(path).toString().replace('\\', '/');
        }));
        task.getRules().getChannelAddress().set(spec.getRules().getChannelAddress());
        task.getRules().getChannelAddressFlags().set(spec.getRules().getChannelAddressFlags());
        task.getRules().getDefaultContentType().set(spec.getRules().getDefaultContentType());
        task.getRules().getProtocol().set(spec.getRules().getProtocol());
        task.getRules().getCustomRules().set(spec.getRules().getCustomRules());
        task.getExtensionClasspath().from(extensions);
    }
    private static String suffix(String name) {
        if (!name.matches(NAME_PATTERN))
            throw new IllegalArgumentException("Asyncwright names must match " + NAME_PATTERN + ": " + name);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
