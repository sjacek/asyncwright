package io.github.sjacek.asyncwright.gradle;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sjacek.asyncwright.api.Diagnostic;
import io.github.sjacek.asyncwright.api.ExtensionExecutionException;
import io.github.sjacek.asyncwright.api.ExtensionResult;
import io.github.sjacek.asyncwright.api.ExtensionSpec;
import io.github.sjacek.asyncwright.api.Severity;
import io.github.sjacek.asyncwright.parser.AsyncApiParser;
import io.github.sjacek.asyncwright.parser.ExtensionExecutor;
import io.github.sjacek.asyncwright.parser.ParseResult;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static io.github.sjacek.asyncwright.gradle.DeclarativeRules.Parameter.*;

public abstract class AsyncApiWork implements WorkAction<AsyncApiWork.Parameters> {
    public interface Parameters extends WorkParameters {
        RegularFileProperty getFile();
        Property<String> getSourcePath();
        DirectoryProperty getProjectDirectory();
        Property<String> getChannelAddress();
        Property<Integer> getChannelAddressFlags();
        Property<String> getContentType();
        Property<String> getProtocol();
        ListProperty<String> getRuleClasses();
        ListProperty<Map<String, String>> getRuleParameters();
        RegularFileProperty getJsonReport();
        RegularFileProperty getTextReport();
        DirectoryProperty getOutputDirectory();
        DirectoryProperty getStagingDirectory();
        Property<String> getGeneratorClass();
        MapProperty<String, String> getGeneratorParameters();
    }
    @Inject protected abstract FileSystemOperations getFiles();

    @Override public void execute() {
        Parameters work = getParameters();
        boolean generation = work.getOutputDirectory().isPresent();
        boolean published = false;
        try {
            if (work.getJsonReport().isPresent())
                getFiles().delete(spec -> spec.delete(work.getJsonReport(), work.getTextReport()));
            if (generation) {
                getFiles().delete(spec -> spec.delete(work.getOutputDirectory(), work.getStagingDirectory()));
            }
            // Parse the declared source name rather than deriving locations from an absolute worker path.
            ParseResult parsed = new AsyncApiParser().parse(work.getSourcePath().get(),
                    Files.readString(work.getFile().get().getAsFile().toPath()));
            List<ExtensionSpec> rules = new ArrayList<>();
            rules.add(new ExtensionSpec(DeclarativeRules.class.getName(), Map.of(
                    CHANNEL_ADDRESS.key(), work.getChannelAddress().get(),
                    CHANNEL_ADDRESS_FLAGS.key(), work.getChannelAddressFlags().get().toString(),
                    CONTENT_TYPE.key(), work.getContentType().get(), PROTOCOL.key(), work.getProtocol().get())));
            for (int index = 0; index < work.getRuleClasses().get().size(); index++)
                rules.add(new ExtensionSpec(work.getRuleClasses().get().get(index), work.getRuleParameters().get().get(index)));
            ExtensionExecutor executor = new ExtensionExecutor(getClass().getClassLoader());
            ExtensionResult result = generation
                    ? executor.generate(parsed, rules, List.of(new ExtensionSpec(work.getGeneratorClass().get(),
                        work.getGeneratorParameters().get())), work.getStagingDirectory().get().getAsFile().toPath())
                    : executor.validate(parsed, rules);
            String text = text(result.diagnostics());
            if (work.getJsonReport().isPresent()) {
                DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
                printer.indentObjectsWith(new DefaultIndenter("  ", "\n"));
                write(work.getJsonReport().get().getAsFile().toPath(), new ObjectMapper().writer(printer)
                        .writeValueAsString(result.diagnostics()) + "\n");
                write(work.getTextReport().get().getAsFile().toPath(), text);
            }
            for (Diagnostic diagnostic : result.diagnostics()) {
                String line = format(diagnostic, work.getProjectDirectory().get().getAsFile().toPath().resolve(diagnostic.file()).toString());
                if (diagnostic.severity() == Severity.ERROR) Logging.getLogger(AsyncApiWork.class).error(line);
                else Logging.getLogger(AsyncApiWork.class).warn(line);
            }
            if (!result.successful()) throw new GradleException("AsyncAPI validation failed with "
                    + result.diagnostics().stream().filter(d -> d.severity() == Severity.ERROR).count() + " error(s)");
            if (generation) {
                // Sync from a fresh staging directory removes obsolete outputs, including empty generation.
                Files.createDirectories(work.getStagingDirectory().get().getAsFile().toPath());
                getFiles().sync(spec -> {
                    spec.from(work.getStagingDirectory());
                    spec.into(work.getOutputDirectory());
                });
                Files.createDirectories(work.getOutputDirectory().get().getAsFile().toPath());
                published = true;
            }
        } catch (IOException | ExtensionExecutionException exception) {
            throw new GradleException("Asyncwright worker failed for " + work.getSourcePath().get(), exception);
        } finally {
            if (generation) {
                if (!published) getFiles().delete(spec -> spec.delete(work.getOutputDirectory()));
                getFiles().delete(spec -> spec.delete(work.getStagingDirectory()));
            }
        }
    }

    private static void write(Path file, String contents) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }
    private static String text(List<Diagnostic> diagnostics) {
        StringBuilder result = new StringBuilder();
        for (Diagnostic diagnostic : diagnostics)
            result.append(format(diagnostic, diagnostic.file())).append('\n');
        return result.toString();
    }
    private static String format(Diagnostic diagnostic, String file) {
        return file + ":" + diagnostic.line() + ":" + diagnostic.column()
                + ": [" + diagnostic.ruleId() + "] " + diagnostic.message();
    }
}
