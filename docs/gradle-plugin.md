# Gradle plugin

The plugin ID is `io.github.sjacek.asyncwright`. It validates the same
[AsyncAPI 3.0.0 subset](validation.md) as the parser. It does not require Node.js.
Version [0.1.0 is available from the Plugin Portal](https://plugins.gradle.org/plugin/io.github.sjacek.asyncwright/0.1.0),
including the plugin marker, parser/API, source and Javadoc artifacts.
See the [release guide](releases.md) for publication and consumer verification.
Start with the [complete minimal consumer setup](../README.md#quick-start), including
repositories, the plugin version, a contract and the validation command. The model
distribution `1.0.0-RC4-asyncwright.3` is already available from Maven Central.

## Validation DSL

The runnable `examples/gradle-consumer` build resolves the plugin directly from this
checkout through `pluginManagement.includeBuild`. Run from the repository root using
the public model:

```powershell
.\gradlew.bat -p examples/gradle-consumer check '-PasyncwrightModelRepository=' --configuration-cache-problems=fail
```

The empty override disables an existing local development model repository. The example
uses Maven Central, with an optional file repository only when a development path is set.
Plugin runtime dependencies are resolved in the consumer's plugin-management repositories;
repositories of the included plugin build are not inherited.

After resolving the plugin using the versioned setup in the quick start, or the included
build for development:

```groovy
plugins {
    id 'io.github.sjacek.asyncwright'
}

asyncwright {
    specs {
        platformEvents {
            file = layout.projectDirectory.file('src/main/asyncapi/events.yaml')
            rules {
                channelAddress ~/^events\.[a-z0-9.-]+$/
                requireDefaultContentType 'application/json'
                requireProtocol 'amqp'
            }
        }
    }
}
```

`check` depends on the aggregate `asyncApiValidate`, which depends on
`asyncApiValidatePlatformEvents`. Validation writes
`build/reports/asyncapi/platformEvents.json` (the diagnostic array) and `.txt`.
Warnings allow the build to succeed; errors fail it after collecting diagnostics.
Reports use project-relative paths and LF line endings; console messages resolve those
paths against the consumer project directory. No model is serialized into the report.

The file must be inside the consumer project directory. Specification and generator
names must match `[a-zA-Z][a-zA-Z0-9]*`; names producing the same task name are rejected.
Specifications are optional, and configuring the plugin does not read their documents.

Imperative integrations can use the public `AsyncwrightPlugin` constants `PLUGIN_ID`,
`EXTENSION_NAME`, `EXTENSIONS_CONFIGURATION_NAME`, `VALIDATE_TASK_NAME` and
`GENERATE_TASK_PREFIX`. The plugin ID has an explicit stable value; it is not derived
from a Java package that might be renamed. The `plugins` block continues to use its
literal ID, because plugin implementation classes are not available before resolution.

The address rule applies to all root channels, resolving references to component
definitions. It requires a textual address that matches the complete expression;
Groovy `Pattern` flags are retained. The content-type rule requires an exact root
`defaultContentType`. The protocol rule checks every declared root server, resolving
component references. It does not create a server when none is declared. These are
opt-in project rules, in addition to the parser's structural and semantic validation.

Kotlin DSL uses the same typed properties:

```kotlin
asyncwright {
    specs {
        register("events") {
            file.set(layout.projectDirectory.file("contracts/events.yaml"))
            rules { channelAddress("^events[.].+$"); requireProtocol("amqp") }
        }
    }
}
```

## Project extensions and generators

Put implementations of the [public Java API](extensions.md) in a Java subproject
or included build, compile them against the plugin artifact, and add their JAR to
`asyncwrightExtensions`:

```groovy
dependencies {
    asyncwrightExtensions project(':contract-rules')
}

asyncwright.specs.platformEvents {
    rules {
        rule('my.contracts.ChannelPrefixRule') {
            parameters.put('prefix', 'events.')
        }
    }
    generators {
        channels {
            className = 'my.contracts.ChannelCatalogGenerator'
            parameters.put('packageName', 'my.events')
        }
    }
}
```

The extension subproject uses a `compileOnly` dependency on the plugin artifact
(`io.github.sjacek.asyncwright:asyncwright-gradle-plugin:0.1.0` for the current release).
The plugin JAR includes the parser and public API; its API dependencies provide
the typed model and Jackson 2. Plugin Portal and Maven Central supply the published
plugin and model dependencies. TestKit also exercises file-based compilation
against the plugin-under-test classpath, without relying on a public repository.
For an included extension build, declare an ordinary module dependency and Gradle
composite dependency substitution. `buildSrc` is not supported as an extension source.

For a rule class already available on the build-script classpath, the DSL also accepts
`Class<? extends AsyncApiRule>`, with or without a configuration action:

```groovy
rules {
    rule(my.contracts.ChannelPrefixRule.class) { parameters.put('prefix', 'events.') }
    rule(my.contracts.RequiredMessagesRule.class)
}
```

These overloads retain only `Class.getName()` (including binary names of nested classes).
They do not instantiate the class or pass a `Class` object to the worker. The rule JAR
must also be available to the worker, normally through `asyncwrightExtensions`.
A dependency in `asyncwrightExtensions` alone does not expose its classes to the build
script. The rule's build-script classloader must also see its API dependencies; the
extension's `compileOnly` dependency alone does not supply them at runtime. Use the
string overload for subproject/included-build classes built later.

Run `asyncApiGeneratePlatformEventsChannels` to validate and generate into
`build/generated/asyncapi/platformEvents/channels`. Generation is explicit; it is not
automatically wired into compilation or `check`. Wire the task's output provider into
the appropriate source set in the consumer project when needed.

Each callback receives its own model view. Rules run before generation; validation
errors prevent generator invocation. A fresh task-local staging directory is published
only after successful generation. Previous outputs and incomplete staging are removed
on worker failure; successful synchronization removes obsolete files. Generated outputs
must use a dedicated directory strictly below the project's `buildDirectory` and must
not contain the input document. Avoid overlapping output directories between tasks.
The declared output provider validates this location before Gradle can restore files
from the build cache, including executions that skip the task action.

For direct task registration, `AsyncApiModelTask` is the abstract public base, and
`AsyncApiGenerate` is its cacheable implementation:

```groovy
tasks.register('generateChannels', io.github.sjacek.asyncwright.gradle.AsyncApiGenerate) {
    file = layout.projectDirectory.file('contracts/events.yaml')
    extensionClasspath.from(configurations.asyncwrightExtensions)
    generatorClass = 'my.contracts.ChannelCatalogGenerator'
    parameters.put('packageName', 'my.events')
    outputDirectory = layout.buildDirectory.dir('generated/channels')
}
```

## Worker isolation and caching

Work uses Gradle `WorkerExecutor.classLoaderIsolation`. The plugin implementation,
parser/API and its dependencies form the worker implementation classpath;
`asyncwrightExtensions` supplies additional runtime dependencies. Live models,
extension instances and script closures do not cross the worker boundary. Exceptions
retain their original causes and fail the task. This is classloader isolation for
trusted Java code, not a process or filesystem sandbox.

Validation and DSL generator tasks are cacheable. Inputs include the document with
relative path sensitivity, its complete project-relative name, bundled schema version,
built-in rules (including regex flags), ordered custom rule class names and text
parameters, generator class/parameters and extension `@Classpath`. Gradle additionally
fingerprints the task/worker implementation classpath. Absolute project locations are
internal properties; reports contain no absolute checkout path. Extensions must remain
deterministic and use only declared inputs. Additional environmental inputs of a custom
generator must be declared by its task, or caching must be disabled for that task.

TestKit checks Groovy/Kotlin DSL, `check`, failure reports, configuration cache reuse,
build cache restoration, relocation, extension JAR/parameter/input invalidation,
included-build extensions, Jackson 3 on the host, the packaged plugin and output cleanup.
Regressions also cover regex flags across the worker/cache boundary, empty generation
and output guards for both the build directory itself and directories containing the input.
Run `:gradle-plugin:test :gradle-plugin:validatePlugins`. Set `-PtestGradleVersion=9.0.0`
to select a consumer Gradle distribution; the default uses the running build's distribution
and JDK. The compatibility workflow defines eight Gradle/JDK pairs: 9.0.0, 9.1.0 and
9.8.1 on JDK 17/21/25, excluding 9.0.0 on JDK 25. CI results must be reviewed before
claiming the entire matrix or releasing the plugin.

The workflow builds the selected model distribution into an isolated file repository,
checks its version against the catalog and records its resolved commit. Development CI
uses `ASYNCWRIGHT_MODEL_REF` or the distribution branch; release preparation requires an
immutable full commit SHA. This bootstrap makes CI independent of a developer's Maven cache.
Public hosting and submission remain separate external steps; preparation does not upload.
