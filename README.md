# Asyncwright

Gradle tooling for contract-first AsyncAPI validation and model-based rules and generators.
The project currently contains the Gradle scaffold and the F0 experiment, not the F1 parser
or the F2 plugin implementation. Java bytecode targets 17; the Wrapper uses Gradle 9.8.1.

Notable changes are recorded in the [changelog](CHANGELOG.md).

Modules:

- `parser`: future Gradle-independent parser; F0 has its own source set and dependency graph.
- `gradle-plugin`: `java-gradle-plugin` scaffold depending on `parser`. Plugin ID registration,
  publication and the consumer DSL will be added in F2, after the model decision.

## Build

Use JDK 17 or newer supported by the selected Gradle runtime:

```powershell
.\gradlew.bat check assemble --configuration-cache-problems=fail
```

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
license; the eventual fork artifact has not yet been selected or wired into this scaffold.
Full interpretation and the D1 consequence are maintained in the workspace wiki:
`wiki/docs/architecture/background/analysis-asyncapi-gradle-plugin.md`, section 10.
