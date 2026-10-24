# Asyncwright

Gradle tooling for contract-first AsyncAPI validation and model-based rules and generators.
Use Asyncwright to check messaging contracts during local builds and CI, enforce project
conventions, and generate project-specific artifacts from a typed AsyncAPI model. Validation
integrates with Gradle's `check`; custom Java generators run through explicit generation tasks.
The plugin supports a defined subset of AsyncAPI **3.0.0**, without requiring Node.js.
See the [Gradle DSL and usage examples](docs/gradle-plugin.md) and
[supported features and limitations](docs/validation.md).

The Gradle-independent Java parser and extension API completed F1 acceptance for the
[documented 0.1 scope](docs/validation.md#f1-acceptance). The
[Gradle plugin](docs/gradle-plugin.md) adds a specification DSL, validation and generator
tasks, isolated workers and TestKit coverage. The project also retains a separate compatibility experiment. Java bytecode
targets 17; the Wrapper uses Gradle 9.8.1.

Notable changes are recorded in the [changelog](CHANGELOG.md).

## Quick start

Requires Gradle 9.0 or newer and a JDK supported by that Gradle version (Java 17 or newer).
Version [0.1.0 is available from the Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.github.sjacek.asyncwright/0.1.0).
The following setup resolves the plugin and its model from public repositories.

For a consuming project, add the public plugin repositories in `settings.gradle`:

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
rootProject.name = 'messaging-contracts'
```

Apply and configure the plugin in `build.gradle`:

```groovy
plugins {
    id 'io.github.sjacek.asyncwright' version '0.1.0'
}

asyncwright {
    specs {
        events {
            file = layout.projectDirectory.file('contracts/events.yaml')
            rules { requireProtocol 'amqp' }
        }
    }
}
```

Create `contracts/events.yaml`:

```yaml
asyncapi: 3.0.0
info:
  title: Messaging contracts
  version: 1.0.0
servers:
  broker:
    host: localhost
    protocol: amqp
channels:
  events:
    address: events.created
```

Run `./gradlew check` (or `.\gradlew.bat check` on Windows). Asyncwright validates the
document and runs the configured project rule; errors fail the build. Diagnostics are
written to `build/reports/asyncapi/events.json` and `events.txt`. You can run only this
specification with `asyncApiValidateEvents`.

For custom Java rules and generators, see the [Gradle DSL guide](docs/gradle-plugin.md),
[extension API](docs/extensions.md), and [complete consumer with an extension subproject](examples/published-consumer).
Generation is explicit and uses a supplied generator; applying the plugin alone does not
generate Java DTOs. The [validation guide](docs/validation.md) lists supported constructs
and limitations.

Modules:

- `parser`: document loading, structural validation, local reference resolution and diagnostics;
  F0 has its own source set and dependency graph.
- `gradle-plugin`: `io.github.sjacek.asyncwright`, with the parser/API included in its JAR;
  DSL, cacheable tasks, Worker API and consumer TestKit tests. Version `0.1.0` is published;
  see the [release guide](docs/releases.md).
- `extension-examples`: ordinary Java rule/generator examples with their own tests;
  this development module is not part of the plugin's dependency graph.

## Build

The model fork `1.0.0-RC4-asyncwright.3` is available in Maven Central. Compile and package
the plugin using public dependencies:

```powershell
.\gradlew.bat assemble '-PasyncwrightModelRepository=' --configuration-cache-problems=fail
```

The empty override also disables any development model path in `gradle/local.properties`.
To try changes to the plugin itself, run the [development consumer](examples/gradle-consumer)
against the included build:

```powershell
.\gradlew.bat -p examples/gradle-consumer check '-PasyncwrightModelRepository=' --configuration-cache-problems=fail
```

The full `check` build includes prepublication HTTP consumer tests that require a staged
model file repository as a test fixture. For those tests, use a checkout of the model's
distribution branch and run `tools/install-local.ps1 -Repository C:/path/to/model-repository`
(JDK 21 with Maven Toolchains is required for the model tests). Then run:

```powershell
.\gradlew.bat check assemble --configuration-cache-problems=fail `
  '-PasyncwrightModelRepository=C:/path/to/model-repository'
```

For IDE imports and builds without command-line parameters, copy
`gradle/local.properties.example` to `gradle/local.properties` and set
`asyncwrightModelRepository` to the same repository path. Use forward slashes in Windows
paths. This local file is ignored by Git; the `-P` option overrides its value.
Reload the Gradle project in the IDE after configuring the repository.

Generate the combined API/parser/Gradle Javadoc with `:gradle-plugin:javadoc`; the HTML
is written to `gradle-plugin/build/docs/javadoc`. Documentation warnings fail the build.
The ordinary verification build also checks Javadoc while preparing artifacts for consumer tests.

## Parser dependency baseline

Dependency versions are pinned in `gradle/libs.versions.toml`:

| Dependency | Version |
| --- | --- |
| Jackson BOM | 2.22.4 |
| NetworkNT JSON Schema Validator (Jackson 2 line) | 2.0.8 |
| Joni regular expressions | 2.2.6 |
| Official AsyncAPI schema package | v6.11.1, commit `e609fc2341007395d75df5756fc6fccf662c2087` |

Only the AsyncAPI **3.0.0** schema is bundled. The schema package release does not expand
the supported document versions. The schema and its internal `$id` references are loaded
from local resources; remote schema fetching is disabled in the dependency checks.
Schema validation alone does not implement the semantic validation or document `$ref` resolver.
Source checksums and licenses are recorded in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

The bundled schema is an unchanged upstream resource with a checksum regression check.
In IntelliJ IDEA, exclude this file from the `JsonSchemaRefReference` and
`RegExpRedundantEscape` inspections using a file scope if they report unresolved example
references or redundant escapes. Keep these IDE preferences local rather than editing the schema.

The selected coordinates for the separately maintained model fork are
`io.github.sjacek.jasyncapi:asyncapi-core:1.0.0-RC4-asyncwright.3`, based on
[`sjacek/jasyncapi` at `05116f0d`](https://github.com/sjacek/jasyncapi/commit/05116f0d99f9e3f3c1b7d1aa8491f120364acf91).
The parser resolves this artifact from Maven Central by default. An explicit
`asyncwrightModelRepository` selects a development file repository restricted to the fork
group; it must contain the parent POM, model POM and model JAR. `mavenLocal()` is not consulted.
Distribution changes are separate from the upstream bug-fix branch. The published model
does not require a sibling checkout or a local repository in a consuming project.
The [published-consumer example](examples/published-consumer) uses only Maven coordinates and
public repository defaults, with no local paths or included build. It resolves plugin
`0.1.0` from the Portal and model `.3` from Maven Central. TestKit also verifies the same
project over staged HTTP repositories before publication. This is separate from the
composite-build development example.

## Initial Java API

```java
import com.asyncapi.v3._0_0.model.channel.Channel;
import io.github.sjacek.asyncwright.parser.AsyncApiParser;
import java.io.IOException;
import java.nio.file.Path;

public class ParseExample {
    public static String parse(Path projectRoot, Path file) throws IOException {
        var result = new AsyncApiParser().parse(projectRoot, file);
        if (result.successful()) {
            var document = result.document().orElseThrow();
            var model = document.model(); // AsyncAPI document, retaining $ref values
            var channel = document.references()
                .resolve("/operations/send/channel", Channel.class);
        }
        return result.diagnosticsJson();
    }
}
```

`AsyncApiParser` is in `io.github.sjacek.asyncwright.parser`; diagnostics and source locations
are in `io.github.sjacek.asyncwright.api`. The parser loads the bundled schema without network
retrieval, reports project-relative paths with one-based positions, and stops after syntax
or structural errors. It checks reference targets, unresolved cycles, operation message
membership, channel address parameters and root operation/channel reference locations.
Parameter definitions must match address placeholders; component definitions may reference
other components. Schema recursion is allowed; pure reference
cycles are errors. The resolver returns target copies without expanding the document graph.
Before returning a typed model, the parser checks that its serialization preserves source
values. Added model defaults are allowed; silent omissions or changed values are errors.

Ignored reference siblings produce warnings and remain available in the source tree. Example
data and extension values are not treated as document references. YAML aliases, external
references, traits and non-default schema formats are currently unsupported. Broader parser
conformance remains under development; the Gradle DSL and worker integration are available
in the published `0.1.0` plugin.
Schema-valid constructs that the typed model cannot preserve are rejected with
`model.preservation` diagnostics; custom binding extensions are one such current limitation.

The [validation scope and diagnostic contract](docs/validation.md) records P3/P5 behavior,
including explicit rejection of foreign schema dialects, `$id` resource scopes and YAML
aliases. Object values in schema `const`, `default` and `examples` are preserved as instance
data by the parser, resolver and extension snapshots. Boolean schemas are supported:
`true` and `false` retain their values in the model, nested schema
positions, default-format wrappers and local reference targets. The typed resolver exposes
them as `api.BooleanSchema`, a subtype of the model's `AsyncAPISchema` with a `value()` method.

## Rule and generator API

The public extension contracts are in `io.github.sjacek.asyncwright.api`: `AsyncApiRule`,
`AsyncApiGenerator`, document/reference views, execution contexts, text parameters and diagnostics.
`ExtensionExecutor` loads classes by name using a supplied classloader and runs the
parse → project rules → generators sequence without Gradle. Each invocation receives its
own model; references stay in the document and are followed through the resolver.

Run the checked-in Java extension example with the public model:

```powershell
.\gradlew.bat :extension-examples:runExample '-PasyncwrightModelRepository=' --configuration-cache-problems=fail
```

This validates channel address prefixes and writes
`examples/extensions/build/generated-example/catalog/channels.json` using typed channel targets.
See the [extension API contract](docs/extensions.md) and [runnable examples](examples/extensions/README.md).
The [Gradle plugin](docs/gradle-plugin.md) provides the `asyncwrightExtensions` configuration,
validation and generator tasks, classloader-isolated Worker API execution and TestKit coverage.
`ResolvedReference` belongs to the public `api` package.

The external five-document corpus has a separate opt-in check:

```powershell
.\gradlew.bat :parser:contractCorpusTest --configuration-cache-problems=fail `
  '-PasyncwrightModelRepository=C:/path/to/model-repository' `
  '-PcontractsDir=C:/path/to/corrected/contracts'
```

Its JSON report is written to `parser/build/reports/asyncapi-corpus/result.json` and includes
source checksums and diagnostics. Ordinary `check` does not require the external documents.

## Public conformance corpus

`check` also runs `:parser:publicCorpusTest` against checked-in public fixtures without
Node.js or network access. The corpus contains 12 unmodified examples from the AsyncAPI
3.0.0 specification release and 68 synthetic positive, negative and capability cases.
The manifest pins fixture hashes, upstream provenance and each parser's reviewed outcome.

An optional comparison uses `@asyncapi/parser` 3.6.6 with core rules enabled and recommended
style rules disabled. See [comparison instructions and known gaps](tools/conformance/README.md)
and the [recorded comparison](docs/conformance/comparison.json). Matching the baseline
does not mean full conformance: the three identified semantic validation gaps are fixed,
while documented capability, model and reference parser differences remain.

## Reproduce F0

The default corpus is the sibling repository
`../../asyncapi-contracts/modules/asyncapi/src/main/asyncapi`.
The five proprietary source contracts are read in place and are not copied into this public project.
An independent checkout can select the same directory explicitly:

```powershell
.\gradlew.bat :parser:f0 '-PcontractsDir=C:/path/to/contracts'
.\gradlew.bat :parser:dependencies --configuration f0RuntimeClasspath
```

The probe uses `com.asyncapi:asyncapi-core:1.0.0-RC4` with the Jackson **2.22.4** BOM,
overriding RC4's older transitive Jackson dependencies. The BOM aligns the Jackson modules
and includes upstream security fixes. `f0JacksonVersion` can select another BOM for a
compatibility experiment. The recorded 2.17.0 and 2.18.2 runs are historical evidence;
those versions have known vulnerabilities and are not the default dependency configuration.

F0 compares source trees with the model serialized back to JSON, probes typed AMQP bindings,
dereferenced operations, recursive schemas, extensions and invalid inputs. The small dereferencer
is experimental and does not implement the production resolver or specification validation.
Model-generated null/empty fields absent from the source are ignored; other differences are recorded.
AMQP's inserted `exchange.vhost: /` default is also reported separately from data loss.
Upstream stderr is captured because RC4 sometimes catches mapping errors and drops map entries.
Removing `nullable` is a diagnostic experiment only and never makes a real contract pass P1.

Outputs: `parser/build/reports/f0/result.json` and `upstream-stderr.log`.
**An exit code of 1 is intentional when P1 fails.** F0 is not wired into `check`, which
compiles the experiment without requiring the sibling repository or accepting its negative verdict.

The current recorded run and contract SHA-256 hashes are in
[docs/f0/result-jackson-2.22.4.json](docs/f0/result-jackson-2.22.4.json).
P1 failed on RC4: swallowed mapping errors lose schemas, inlined operation references lose
operations, and recursive schema references use `AsyncAPISchema` rather than `Reference`.
Typed AMQP bindings work. The user selected a separate fork of
[asyncapi/jasyncapi](https://github.com/asyncapi/jasyncapi), preserving upstream history and
license; the selected fork coordinates above are integrated through an explicitly configured
repository and have not yet been published publicly.
Full interpretation and the D1 consequence are maintained in the workspace wiki:
`wiki/docs/architecture/background/analysis-asyncapi-gradle-plugin.md`, section 10.
