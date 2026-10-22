package io.github.sjacek.asyncwright.gradle;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.*;

@CacheableTask
public abstract class AsyncApiValidate extends AsyncApiTask {
    @OutputFile public abstract RegularFileProperty getJsonReport();
    @OutputFile public abstract RegularFileProperty getTextReport();
    @TaskAction public void validate() {
        submit(getJsonReport().get().getAsFile(), getTextReport().get().getAsFile(), null, null, java.util.Map.of());
    }
}
