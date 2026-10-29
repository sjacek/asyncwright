package io.github.sjacek.asyncwright.gradle;

import io.github.sjacek.asyncwright.api.AsyncApi30;
import io.github.sjacek.asyncwright.api.Avro19;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.workers.WorkerExecutor;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.io.File;
import java.util.Map;
import java.util.List;

/** Common declared inputs. Models and extension instances never cross the worker boundary. */
@DisableCachingByDefault(because = "Base task; concrete task types declare their caching contract.")
public abstract class AsyncApiTask extends DefaultTask {
    private static final String STAGING_DIRECTORY = "staging";
    private final AsyncApiRules rules;
    /**
     * Initializes rules, project/build directories and the bundled schema version.
     * The source name defaults to the input's project-relative path.
     */
    public AsyncApiTask() {
        rules = getProject().getObjects().newInstance(AsyncApiRules.class);
        getProjectDirectory().convention(getProject().getLayout().getProjectDirectory());
        getBuildDirectory().convention(getProject().getLayout().getBuildDirectory());
        getSchemaVersion().convention(AsyncApi30.VERSION);
        var schemas = getProject().files(getFile().map(file -> file.getAsFile().getParentFile()))
                .getAsFileTree().matching(pattern -> pattern.include(Avro19.FILE_PATTERN));
        getSchemaFiles().from(schemas);
        // Preserve explicit producer dependencies while snapshotting only discovered files below.
        dependsOn(getSchemaFiles());
        var root = getProjectDirectory();
        getSourcePath().convention(getFile().map(file -> {
            var base = root.get().getAsFile().toPath().toAbsolutePath().normalize();
            var path = file.getAsFile().toPath().toAbsolutePath().normalize();
            if (!path.startsWith(base)) throw new IllegalArgumentException("AsyncAPI document must be inside the project directory");
            return base.relativize(path).toString().replace('\\', '/');
        }));
    }
    /**
     * Returns the single input specification.
     * @return required JSON or YAML file inside the project directory
     */
    @InputFile @PathSensitive(PathSensitivity.RELATIVE) public abstract RegularFileProperty getFile();
    /**
     * Returns the declared Avro files, tracked before workers run, including when no file is referenced.
     * This configuration collection is internal because the concrete leaves and portable names
     * are exposed as separate inputs below. Its producer tasks are explicit dependencies.
     * @return schema input files/file trees, defaulting to .avsc files below the document directory
     */
    @Internal public abstract ConfigurableFileCollection getSchemaFiles();
    /**
     * Discovers concrete files on every input snapshot, including configuration-cache reuse.
     * Tracking a tree rooted at the project itself would claim unrelated generator outputs
     * as implicit inputs. Eagerly mapping a provider to a file set would freeze discovery
     * in configuration cache. Keep the configured tree live and expose its current leaves.
     * @return current declared files; producer dependencies are retained by schemaFiles
     */
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public java.util.Set<File> getSchemaFileInputs() { return getSchemaFiles().getFiles(); }
    /**
     * Returns complete relative input names in addition to content fingerprints. Moving two identical
     * same-named files between directories must invalidate allowlist and diagnostic output correctly.
     * @return sorted project-relative schema file names
     */
    @Input public List<String> getSchemaFilePaths() {
        var base = getProjectDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
        // Input validation must run before UP-TO-DATE/FROM-CACHE, not just inside the worker.
        // A symlink retargeted outside the project can otherwise retain identical content hashes.
        inputPath(base, getFile().get().getAsFile());
        return getSchemaFiles().getFiles().stream().map(file -> {
            if (file.isDirectory()) throw new IllegalArgumentException("Use a fileTree for schemaFiles directories");
            return inputPath(base, file);
        }).sorted().toList();
    }

    /** Checks authority independently of content hashes, before a cached task can skip its worker. */
    private static String inputPath(java.nio.file.Path base, File input) {
        var path = input.toPath().toAbsolutePath().normalize();
        if (!path.startsWith(base)) throw new IllegalArgumentException("AsyncAPI inputs must be inside the project directory");
        try {
            if (java.nio.file.Files.exists(path) && !path.toRealPath().startsWith(base.toRealPath()))
                throw new IllegalArgumentException("Input real path must remain inside the project directory");
            if (java.nio.file.Files.exists(path) && !java.nio.file.Files.isRegularFile(path))
                throw new IllegalArgumentException("AsyncAPI inputs must be regular files");
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Cannot inspect the input real path", exception);
        }
        return base.relativize(path).toString().replace('\\', '/');
    }
    // @InputFile alone fingerprints just the basename of a single file; diagnostics need its complete relative name.
    /**
     * Returns the diagnostic file name, tracked separately for relocatable cache correctness.
     * @return project-relative path with forward slashes
     */
    @Input public abstract Property<String> getSourcePath();
    /**
     * Returns the requested bundled specification schema.
     * @return version; only {@code 3.0.0} is supported
     */
    @Input public abstract Property<String> getSchemaVersion();
    /**
     * Returns nested, cache-tracked validation options.
     * @return declarative and custom rule configuration
     */
    @Nested public AsyncApiRules getRules() { return rules; }
    /**
     * Configures nested validation options.
     * @param action action executed during configuration
     */
    public void rules(Action<? super AsyncApiRules> action) { action.execute(rules); }
    /**
     * Returns additional worker dependencies; build-script classpaths are not passed automatically.
     * @return rule and generator runtime classpath, fingerprinted by Gradle
     */
    @Classpath public abstract ConfigurableFileCollection getExtensionClasspath();
    /**
     * Returns the root used to derive portable source names and clickable console locations.
     * @return project directory, defaulting to the owning project
     */
    @Internal public abstract DirectoryProperty getProjectDirectory();
    /**
     * Returns the boundary within which task-owned generator output must reside.
     * @return owning project's build directory by default
     */
    @Internal public abstract DirectoryProperty getBuildDirectory();
    /**
     * Returns the Gradle service creating isolated worker classloaders.
     * @return injected worker executor
     */
    @Inject protected abstract WorkerExecutor getWorkerExecutor();

    /**
     * Submits one isolated worker and waits for its outcome. Models and extension instances
     * are created inside the worker; only declared inputs cross the boundary.
     * @param jsonReport JSON report destination, or null when generating
     * @param textReport text report destination, or null when generating
     * @param output generator destination, or null for validation
     * @param generator binary generator class name when generating, otherwise null
     * @param parameters explicit generator parameters
     * @throws IllegalArgumentException if the schema version or output boundary is unsupported
     */
    protected final void submit(File jsonReport, File textReport, File output, String generator,
                                Map<String, String> parameters) {
        if (!getSchemaVersion().get().equals(AsyncApi30.VERSION))
            throw new IllegalArgumentException("Only bundled AsyncAPI schema " + AsyncApi30.VERSION + " is available");
        var queue = getWorkerExecutor().classLoaderIsolation(spec -> spec.getClasspath().from(getExtensionClasspath()));
        queue.submit(AsyncApiWork.class, work -> {
            work.getFile().set(getFile());
            work.getSourcePath().set(getSourcePath());
            work.getSchemaFilePaths().set(getSchemaFilePaths());
            work.getProjectDirectory().set(getProjectDirectory());
            work.getChannelAddress().set(rules.getChannelAddress());
            work.getChannelAddressFlags().set(rules.getChannelAddressFlags());
            work.getContentType().set(rules.getDefaultContentType());
            work.getProtocols().set(rules.getProtocols());
            work.getRuleClasses().set(rules.getCustomRules().get().stream().map(e -> e.getClassName().get()).toList());
            work.getRuleParameters().set(rules.getCustomRules().get().stream().map(e -> e.getParameters().get()).toList());
            if (jsonReport != null) work.getJsonReport().set(jsonReport);
            if (textReport != null) work.getTextReport().set(textReport);
            if (output != null) {
                validateGeneratorOutput(output, getBuildDirectory().get().getAsFile(), getFile().get().getAsFile());
                for (File schema : getSchemaFiles()) validateGeneratorOutput(output, getBuildDirectory().get().getAsFile(), schema);
                work.getOutputDirectory().set(output);
                work.getStagingDirectory().set(new File(getTemporaryDir(), STAGING_DIRECTORY));
            }
            if (output != null) work.getGeneratorClass().set(generator);
            work.getGeneratorParameters().set(parameters);
        });
        queue.await();
    }

    /**
     * Checks the output boundary before task execution or cache restoration.
     * @param output dedicated task-owned output directory
     * @param buildDirectory parent boundary; cannot itself be an output
     * @param input specification that must survive output cleanup
     * @throws IllegalArgumentException if output is outside the build directory, equals it, or contains the input
     */
    protected static void validateGeneratorOutput(File output, File buildDirectory, File input) {
        var destination = output.toPath().toAbsolutePath().normalize();
        var build = buildDirectory.toPath().toAbsolutePath().normalize();
        if (!destination.startsWith(build) || destination.equals(build))
            throw new IllegalArgumentException("Generator output must be a dedicated directory below buildDirectory");
        if (input.toPath().toAbsolutePath().normalize().startsWith(destination))
            throw new IllegalArgumentException("Generator output must not contain the input document");
    }
}
