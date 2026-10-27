# Java DTO consumer

Uses the prepared 0.2.0 plugin through includeBuild, its bundled JavaDtoGenerator and
an explicitly staged model `.5`. No Node.js, npm or Modelina process is invoked.
The plugin and model are not yet published. The client and generated DTOs target Java 17.

Run from the Asyncwright source root:

```shell
./gradlew -p examples/java-dto-consumer demo \
  -PasyncwrightModelRepository=/path/to/model-repository \
  --configuration-cache-problems=fail
```

The task generates Event and State, compiles the client and prints
`3000000000 / ready`. See the [backend profile](../../docs/java-dto-generator.md).

## IntelliJ IDEA import

This example is a separate Gradle build with its own settings.gradle, not a subproject
of the root Asyncwright build. In the Gradle tool window, choose **Link Gradle Project**
and select this directory's build.gradle, or open this directory as a Gradle project.
Use JDK 17 or newer supported by the selected Gradle version.

Configure the model .5 file repository in the root build's ignored
gradle/local.properties (or pass the property shown above), then reload the linked
example. Its includeBuild resolves the plugin and makes JavaDtoGenerator and
AsyncApiGenerate available to the build script. Importing only the repository root
does not give nested standalone build scripts that classpath.

IDEA may generate wrapper files inside this example while linking it. They are local
and ignored by Git; the commands above use the versioned wrapper at the repository root.
After linking, rerun code analysis: a report from an earlier commit check does not
refresh automatically when the Gradle classpath changes.
