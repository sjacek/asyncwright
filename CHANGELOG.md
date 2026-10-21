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

### Changed

- Align the compatibility probe's Jackson dependencies with the `2.22.4` BOM to include
  upstream security fixes and override the model library's older transitive dependencies.

### Fixed

- Resolve the contract directory relative to the project root using Gradle's directory API.
- Report missing mapped values explicitly and close the captured diagnostic stream automatically.

### Development status

- This initial development snapshot provides project setup and compatibility experiments.
  AsyncAPI validation and a usable Gradle plugin API are not available yet.
