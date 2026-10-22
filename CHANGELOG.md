# Changelog

Notable changes to Asyncwright are recorded here. Changes under **Unreleased** have not
been published. The current development version is `0.1.0-SNAPSHOT`.

## Unreleased

### Changed

- Centralized specification metadata, field names, built-in diagnostic IDs and Gradle
  names; shared typed rule parameters and JSON Pointer construction. Diagnostic wire
  values, report formats and validation behavior remain unchanged.

### Added

- Optional class-based rule registration for rule types already available to the build
  script, retaining binary class names for isolated worker execution.
- Development Gradle plugin with a specification DSL, declarative/project rules, cacheable
  validation and generator tasks, relative JSON/text reports and isolated Worker API execution.
- TestKit coverage for consumer builds, caching and relocation, extension changes, Jackson
  isolation and generator output cleanup; an eight-pair Gradle/JDK compatibility workflow.
- F1 acceptance record for the documented parser and Java extension API scope, with a clean
  verification run, reviewed limitations and an unchanged 80-case JS comparison baseline.
- Boolean schema support, with a public `BooleanSchema` leaf and regressions covering nested
  schemas, local references, model serialization, extension rules and generators.
- Documented validation scope and diagnostic contract, with additional public P3/P5 regressions
  and explicit tracking of reviewed parser differences and shared limitations.
- Public rule and generator API with document/reference views, immutable text parameters,
  source-aware diagnostics and scoped output writes.
- A Gradle-independent extension executor supporting class-name registration, caller-supplied
  classloaders, per-invocation models and explicit extension failures.
- Runnable Java extension examples and tests using an independently compiled extension jar.
- Initial project structure with separate parser and Gradle plugin modules.
- Gradle `9.8.1` Wrapper with distribution checksum verification and compilation targeting
  Java 17 bytecode.
- An experimental compatibility probe for `asyncapi-core:1.0.0-RC4`, covering data
  preservation during serialization, AMQP bindings, references and recursive schemas.
- JSON compatibility reports with input checksums, data differences and mapping errors.
  Contributors can select an external contract directory and compare Jackson versions.
- Development setup and build instructions, MIT licensing and third-party notices.
- Pinned JSON Schema validation dependencies and the official AsyncAPI 3.0.0 schema,
  including upstream license notices and checks for offline loading and validation.
- Regression checks distinguishing optional properties, explicit null values and the
  nonstandard `nullable` keyword under JSON Schema draft-07.
- Initial Java parsing API with source positions, offline schema validation, structured
  diagnostics and a separate resolver retaining document references.
- Reference target and cycle checks, operation message membership and channel parameter rules.
- Opt-in integration checks for an external contract corpus and explicit local fork resolution.
- Public regression corpus with 12 pinned official AsyncAPI 3.0.0 examples and 68 synthetic cases.
- Offline JVM corpus checks and a reproducible JavaScript parser comparison with reviewed differences.

### Changed

- Report unsupported YAML aliases at their source pointer with `unsupported.yaml-alias`.
- Reject foreign schema dialects and `$id` resource identifiers explicitly in the 0.1 scope.
- Move `ResolvedReference` to `io.github.sjacek.asyncwright.api` and expose public document/reference interfaces.
- Align the compatibility probe's Jackson dependencies with the `2.22.4` BOM to include
  upstream security fixes and override the model library's older transitive dependencies.

### Fixed

- Apply a narrow runtime correction to the bundled schema's wrapper condition so boolean schemas
  reach the Schema Object validator; preserve the original upstream resource bytes.
- Resolve boolean schema dependencies and apply the default format for wrappers without `schemaFormat`.
- Validate formats and nested references in component schema wrappers.
- Preserve arbitrary JSON instance objects and numeric precision in schema const/default/examples,
  consistently in the parser, typed resolver and extension models.
- Include transitive reference usages in shared-definition diagnostics and suppress secondary
  operation membership errors when a channel's message set cannot be resolved.
- Locate additional YAML documents correctly, reject nonportable diagnostic input paths and
  order diagnostic ties deterministically.
- Resolve the contract directory relative to the project root using Gradle's directory API.
- Report missing mapped values explicitly and close the captured diagnostic stream automatically.
- Support a Git-ignored local model repository configuration for IDE imports and CLI builds.
- Provide a complete, compilable Java usage example.
- Reject channel parameters that do not occur in the channel address.
- Require root operations to reference root channels and root channels to reference root servers,
  while preserving valid references in component definitions and root aliases.

### Development status

- The Java parser has initial validation and reference coverage. The Gradle plugin API,
  complete conformance checks and public artifact distribution remain under development.
