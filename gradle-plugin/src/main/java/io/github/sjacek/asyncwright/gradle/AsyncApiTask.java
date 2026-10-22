package io.github.sjacek.asyncwright.gradle;

import io.github.sjacek.asyncwright.api.AsyncApi30;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.workers.WorkerExecutor;

import javax.inject.Inject;
import java.io.File;
import java.util.Map;

/** Common declared inputs. Models and extension instances never cross the worker boundary. */
public abstract class AsyncApiTask extends DefaultTask {
    private static final String STAGING_DIRECTORY = "staging";
    private final AsyncApiRules rules;
    public AsyncApiTask() {
        rules = getProject().getObjects().newInstance(AsyncApiRules.class);
        getProjectDirectory().convention(getProject().getLayout().getProjectDirectory());
        getBuildDirectory().convention(getProject().getLayout().getBuildDirectory());
        getSchemaVersion().convention(AsyncApi30.VERSION);
        var root = getProjectDirectory();
        getSourcePath().convention(getFile().map(file -> {
            var base = root.get().getAsFile().toPath().toAbsolutePath().normalize();
            var path = file.getAsFile().toPath().toAbsolutePath().normalize();
            if (!path.startsWith(base)) throw new IllegalArgumentException("AsyncAPI document must be inside the project directory");
            return base.relativize(path).toString().replace('\\', '/');
        }));
    }
    @InputFile @PathSensitive(PathSensitivity.RELATIVE) public abstract RegularFileProperty getFile();
    // @InputFile alone fingerprints just the basename of a single file; diagnostics need its complete relative name.
    @Input public abstract Property<String> getSourcePath();
    @Input public abstract Property<String> getSchemaVersion();
    @Nested public AsyncApiRules getRules() { return rules; }
    public void rules(Action<? super AsyncApiRules> action) { action.execute(rules); }
    @Classpath public abstract ConfigurableFileCollection getExtensionClasspath();
    @Internal public abstract DirectoryProperty getProjectDirectory();
    @Internal public abstract DirectoryProperty getBuildDirectory();
    @Inject protected abstract WorkerExecutor getWorkerExecutor();

    protected final void submit(File jsonReport, File textReport, File output, String generator,
                                Map<String, String> parameters) {
        if (!getSchemaVersion().get().equals(AsyncApi30.VERSION))
            throw new IllegalArgumentException("Only bundled AsyncAPI schema " + AsyncApi30.VERSION + " is available");
        var queue = getWorkerExecutor().classLoaderIsolation(spec -> spec.getClasspath().from(getExtensionClasspath()));
        queue.submit(AsyncApiWork.class, work -> {
            work.getFile().set(getFile());
            work.getSourcePath().set(getSourcePath());
            work.getProjectDirectory().set(getProjectDirectory());
            work.getChannelAddress().set(rules.getChannelAddress());
            work.getChannelAddressFlags().set(rules.getChannelAddressFlags());
            work.getContentType().set(rules.getDefaultContentType());
            work.getProtocol().set(rules.getProtocol());
            work.getRuleClasses().set(rules.getCustomRules().get().stream().map(e -> e.getClassName().get()).toList());
            work.getRuleParameters().set(rules.getCustomRules().get().stream().map(e -> e.getParameters().get()).toList());
            if (jsonReport != null) work.getJsonReport().set(jsonReport);
            if (textReport != null) work.getTextReport().set(textReport);
            if (output != null) {
                validateGeneratorOutput(output, getBuildDirectory().get().getAsFile(), getFile().get().getAsFile());
                work.getOutputDirectory().set(output);
                work.getStagingDirectory().set(new File(getTemporaryDir(), STAGING_DIRECTORY));
            }
            if (output != null) work.getGeneratorClass().set(generator);
            work.getGeneratorParameters().set(parameters);
        });
        queue.await();
    }

    protected static void validateGeneratorOutput(File output, File buildDirectory, File input) {
        var destination = output.toPath().toAbsolutePath().normalize();
        var build = buildDirectory.toPath().toAbsolutePath().normalize();
        if (!destination.startsWith(build) || destination.equals(build))
            throw new IllegalArgumentException("Generator output must be a dedicated directory below buildDirectory");
        if (input.toPath().toAbsolutePath().normalize().startsWith(destination))
            throw new IllegalArgumentException("Generator output must not contain the input document");
    }
}
