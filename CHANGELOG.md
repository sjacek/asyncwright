# Changelog

User-visible changes to Asyncwright. Prepared releases are not publicly available until
publication is confirmed.

## Unreleased

## 0.1.1 — prepared, not published

### Fixed

- Reuse server, channel, operation and message binding maps through local `$ref` values,
  including aliases in `components.*Bindings`. Rules and generators can follow these
  references with the separate resolver while the typed model retains the original URI.
- Report missing binding maps, incompatible target kinds and alias cycles at their source
  locations. Invalid protocol definitions retain their full path and related reference uses.

### Dependencies and upgrading

- Uses model `io.github.sjacek.jasyncapi:asyncapi-core:1.0.0-RC4-asyncwright.4`.
- Existing inline binding maps continue to work. When following a reference to a whole
  binding map, resolve the map first and then select its protocol; see the
  [extension API example](docs/extensions.md#binding-map-references).
- Requirements remain Java 17+ and Gradle 9.0+ on a compatible JDK.

The stable version numbers are prepared for release. Model `.4` and plugin `0.1.1` have
not been published; the public `0.1.0` / model `.3` release remains unchanged.

## [0.1.0] - 2026-10-23

First public release.

### Added

- Validate AsyncAPI 3.0.0 YAML and JSON contracts as part of Gradle's `check` task.
  Invalid contracts fail the build, so the same checks can run locally and in CI.
- Configure multiple named specifications, each with its own validation rules and reports.
  Run all validations with `asyncApiValidate` or select one specification with
  `asyncApiValidate<SpecName>`.
- Get JSON and text diagnostics with the contract path, line, column and JSON Pointer,
  including related locations for reference errors.
- Enforce project conventions with built-in rules for channel addresses, server protocols
  and the default content type.
- Write custom validation rules and generators in Java using the typed AsyncAPI model.
  Follow local references through a separate resolver while retaining `$ref` values in
  the document. Run generators explicitly to produce project-specific files.
- Use boolean schemas (`true` and `false`) and recursive schemas. Preserve JSON values
  in schema `const`, `default` and `examples`, including arbitrary-precision numbers.
- Reuse validation and generation results through Gradle's build cache and configuration
  cache. Custom extensions must use declared inputs and produce deterministic outputs.
- Run validation and custom generators in isolated Gradle workers, without requiring Node.js.
- Start with the [quick start](README.md#quick-start), configure the plugin with
  [Groovy or Kotlin DSL](docs/gradle-plugin.md), and extend it using the
  [Java API and runnable examples](docs/extensions.md).

### Requirements

- Gradle 9.0 or newer and Java 17 or newer. The selected JDK must also be supported by
  the Gradle version running the build.
- The model dependency `io.github.sjacek.jasyncapi:asyncapi-core:1.0.0-RC4-asyncwright.3`
  is available from Maven Central; consumers do not need a local model repository.

### Scope and limitations

- Supports a [documented subset of AsyncAPI 3.0.0](docs/validation.md), rather than full
  specification conformance. Other AsyncAPI versions are not supported.
- References must use local JSON Pointers. External references, traits, YAML aliases,
  schema `$id` resource scopes and non-default schema formats are not supported.
- Example payloads are not validated against their schemas. Documents containing values
  the typed model cannot preserve are rejected with diagnostics rather than silently altered.
- Generation requires a custom generator. This release does not include a Java DTO generator
  or automatically attach generated files to compilation.

[0.1.0]: https://plugins.gradle.org/plugin/io.github.sjacek.asyncwright/0.1.0
