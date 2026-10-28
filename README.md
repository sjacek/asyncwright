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

**Asyncwright 0.2.1** is published in the Gradle Plugin Portal with model
**1.0.0-RC4-asyncwright.5** in Maven Central. The quick start and public consumer use
these released versions; no local model repository or sibling checkout is required.
See the [release guide](docs/releases.md) for the verified source commits and consumer checks.

Since **0.2.0**, the plugin includes a bundled [Java DTO generator](docs/java-dto-generator.md).
The [public DTO example](examples/java-dto-consumer) generates Java models and compiles
a client using the released plugin, without Node.js or a sibling checkout.

## Quick start

Requires Gradle 9.0 or newer and a JDK supported by that Gradle version (Java 17 or newer).
Version [0.2.1 is available from the Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.github.sjacek.asyncwright/0.2.1).
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
    id 'io.github.sjacek.asyncwright' version '0.2.1'
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
Generation is explicit: select the bundled JavaDtoGenerator or a custom generator.
Applying the plugin alone does not generate DTOs. The [validation guide](docs/validation.md) lists supported constructs
and limitations.

Modules:

- `parser`: document loading, structural validation, local reference resolution and diagnostics;
  F0 has its own source set and dependency graph.
- `java-generator`: native Java DTO/enum backend, bundled in the plugin JAR;
- `gradle-plugin`: `io.github.sjacek.asyncwright`, with the parser/API included in its JAR;
  DSL, cacheable tasks, Worker API and consumer TestKit tests. Version `0.2.1` is published;
  see the [release guide](docs/releases.md).
- `extension-examples`: ordinary Java rule/generator examples with their own tests;
  this development module is not part of the plugin's dependency graph.

## Build

This checkout builds released Asyncwright `0.2.1` with model
`1.0.0-RC4-asyncwright.5` from Maven Central. Version 0.2.1 fixes intermittent
`Stream closed` failures when isolated workers load the bundled schema. No DSL,
extension API or generated-output migration is required. Public examples use
released `0.2.1/.5`; see the [release status](docs/releases.md#published-021-patch-release).
Compile and package this checkout without a model checkout:

```powershell
.\gradlew.bat assemble '-PasyncwrightModelRepository=' --configuration-cache-problems=fail
.\gradlew.bat -p examples/gradle-consumer check '-PasyncwrightModelRepository=' --configuration-cache-problems=fail
```

The second command uses the [included-build development consumer](examples/gradle-consumer).
The empty property clears a machine-specific override in ignored gradle/local.properties;
it is unnecessary when no override is configured. The public examples use the released
plugin instead of the included build.

For HTTP publication tests, prepare a fresh model file repository with
`tools/install-local.ps1 -Repository C:/path/to/model-repository` in the separate
`jasyncapi-distribution` checkout at release commit
`e07d9e1ded75711a5180ea435b0ce02c27a89de8`. Model tests require JDK 21 and Maven Toolchains.

The full `check` includes HTTP publication tests that serve this model file
repository as a fixture. Run:

```powershell
.\gradlew.bat check assemble --configuration-cache-problems=fail `
  '-PasyncwrightModelRepository=C:/path/to/model-repository'
```

That file repository is a test fixture, not a requirement for using the published plugin.
Maven Local is not used.

IDE imports resolve model `.5` from Maven Central by default. For model development, copy
`gradle/local.properties.example` to `gradle/local.properties` and set
`asyncwrightModelRepository` to the model repository.
Use forward slashes in Windows paths. This file is ignored by Git; `-P` overrides its value.
Reload the Gradle project after changing the repository configuration.

Generate the combined API/parser/Gradle Javadoc with `:gradle-plugin:javadoc`; the HTML
is written to `gradle-plugin/build/docs/javadoc`. Documentation warnings fail the build.
The ordinary verification build also checks Javadoc while preparing artifacts for consumer tests.

## Parser dependency baseline

Dependency versions are pinned in `gradle/libs.versions.toml`:

| Dependency | Version |
| --- | --- |
| jasyncapi fork (Maven Central) | 1.0.0-RC4-asyncwright.5 |
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

The published `0.2.1` release uses the separately maintained model fork
`io.github.sjacek.jasyncapi:asyncapi-core:1.0.0-RC4-asyncwright.5`, with model sources at
[`6de0c02e`](https://github.com/sjacek/jasyncapi/commit/6de0c02e884a9336f727a6cc797ac8eb304c0e87).
The distribution was released from `e07d9e1ded75711a5180ea435b0ce02c27a89de8`.
Both parent POM and model resolve from Maven Central. Original packages, Apache-2.0
licensing and upstream attribution are preserved.

An explicit `asyncwrightModelRepository` can select a development file repository restricted
to the fork group. It must contain the parent POM, model POM and model JAR. This override is
for model development and HTTP publication tests; consuming projects need neither it nor
a sibling checkout. `mavenLocal()` is not consulted.

The [published-consumer example](examples/published-consumer) resolves plugin `0.2.1` from
the Portal and model `.5` from Maven Central by default, with no local paths or included
build. TestKit also verifies this project over staged HTTP repositories. The separate
composite-build example is for developing the plugin itself.

The parser accepts references to whole server, channel, operation and message
binding maps. Models keep `$ref`; follow the map first, then select its protocol through
the separate resolver. See the [binding-map example](docs/extensions.md#binding-map-references).

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
in the published `0.2.1` plugin.
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

Run the checked-in Java extension example using the development model repository:

```powershell
.\gradlew.bat :extension-examples:runExample '-PasyncwrightModelRepository=C:/path/to/model-repository' --configuration-cache-problems=fail
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
3.0.0 specification release and 96 synthetic positive, negative and capability cases.
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
license. The selected fork coordinates above are now published in Maven Central; the
recorded upstream RC4 failure remains historical evidence for that decision.
Full interpretation and the D1 consequence are maintained in the workspace wiki:
`wiki/docs/architecture/background/analysis-asyncapi-gradle-plugin.md`, section 10.
