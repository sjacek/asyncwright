# Changelog

Notable changes to Asyncwright are recorded here. Changes under **Unreleased** have not
been published. The current development version is `0.1.0-SNAPSHOT`.

## Unreleased

### Added

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
- Public regression corpus with 12 pinned official AsyncAPI 3.0.0 examples and 48 synthetic cases.
- Offline JVM corpus checks and a reproducible JavaScript parser comparison with reviewed differences.

### Changed

- Align the compatibility probe's Jackson dependencies with the `2.22.4` BOM to include
  upstream security fixes and override the model library's older transitive dependencies.

### Fixed

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
