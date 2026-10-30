package io.github.sjacek.asyncwright.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseVerificationTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @CsvSource({"0.1.0,v0.1.0,true", "0.1.1,v0.1.1,true", "0.1.0-SNAPSHOT,v0.1.0,false",
            "0.1.1-SNAPSHOT,v0.1.1,false", "0.1.1,v0.1.0,false", "0.1.0,v01.1.0,false"})
    void requiresAStableTagMatchingTheEvaluatedVersion(String version, String tag, boolean accepted) throws Exception {
        Files.copy(Path.of(System.getProperty("asyncwright.projectDirectory"), "gradle/release-verification.gradle"),
                root.resolve("release-verification.gradle"));
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'release-guard'\n");
        Files.writeString(root.resolve("build.gradle"), "version = '" + version + "'\napply from: 'release-verification.gradle'\n");
        GradleRunner runner = GradleRunner.create().withProjectDir(root.toFile())
                .withTestKitDir(new File(System.getProperty("asyncwright.testKitDir")))
                .withGradleInstallation(new File(System.getProperty("asyncwright.testGradleHome")))
                .withArguments(List.of("verifyReleaseTag", "-PreleaseTag=" + tag,
                        "--offline", "--configuration-cache", "--configuration-cache-problems=fail"));
        if (accepted) runner.build();
        else assertTrue(runner.buildAndFail().getOutput().contains("releaseTag"));
    }
}
