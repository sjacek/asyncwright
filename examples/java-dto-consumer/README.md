# Java DTO consumer

Uses released **Asyncwright 0.2.1** from the Gradle Plugin Portal and model
**1.0.0-RC4-asyncwright.5** from Maven Central. No included plugin build, local model
repository, Node.js, npm or Modelina process is required. The client and generated
DTOs target Java 17.

With an installed Gradle 9.0+ and a compatible JDK 17+, run from this directory:

```shell
gradle demo --configuration-cache-problems=fail
```

From the Asyncwright source root, its wrapper can run the standalone example:

```shell
./gradlew -p examples/java-dto-consumer demo --configuration-cache-problems=fail
```

The task generates Event and State, compiles the client and prints
`3000000000 / ready`. See the [backend profile](../../docs/java-dto-generator.md).
This example can be copied outside the checkout; only public repositories are used.
For an included-build source consumer, use [gradle-consumer](../gradle-consumer).

## IntelliJ IDEA import

This example is a separate Gradle build with its own settings.gradle, not a subproject
of the root Asyncwright build. In the Gradle tool window, choose **Link Gradle Project**
and select this directory's build.gradle, or open this directory as a Gradle project.
Use JDK 17 or newer supported by the selected Gradle version.

Reload the linked project to resolve the published plugin and its JavaDtoGenerator,
GeneratorOptions and AsyncApiGenerate classes. Importing only the repository root
does not give nested standalone build scripts that classpath.

IDEA may generate wrapper files inside this example while linking it. They are local
and ignored by Git; the commands above use the versioned wrapper at the repository root.
After linking, rerun code analysis to refresh the classpath diagnostics.
