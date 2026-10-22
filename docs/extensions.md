# Extension API (0.1)

Extensions implement contracts in `io.github.sjacek.asyncwright.api`. They depend on the
parser/API artifact and its model/Jackson dependencies, without Gradle types. The
`extension-examples` development module demonstrates that dependency and is checked by
the normal build. It is separate from the two production modules.

| Contract | Purpose |
| --- | --- |
| `AsyncApiRule` | `validate(ExtensionContext)` returns project diagnostics. |
| `AsyncApiGenerator` | `generate(GeneratorContext)` writes outputs and returns diagnostics. |
| `ExtensionSpec` | Binary class name and an immutable `Map<String, String>` of parameters. |
| `ExtensionContext` | Document view, text parameters, required-parameter lookup and diagnostic factory. |
| `GeneratorContext` | The same input plus `GeneratedFiles` output access. |
| `AsyncApiDocumentView` | Typed model, source copy, positions and reference access. |
| `AsyncApiReferences` | Target lookup, typed lookup and related reference usage locations. |
| `ResolvedReference` | Target pointer and a defensive copy of its source value. |
| `ExtensionResult` | Combined ordered diagnostics, success status and sorted relative output paths. |
| `ExtensionExecutionException` | Extension class/kind and original cause for loading or execution failures. |

## Register and execute

`ExtensionExecutor`, in the `parser` package, is the plain Java host. Its constructor accepts
the classloader containing extension jars and their dependencies. The no-argument constructor
uses the executor's own loader. Registration is explicit; service discovery is not required.
Classes must implement the expected interface and have a public no-argument constructor.
Use binary names (`Class.getName()`, including `$` for nested classes).

The complete compiled host is [ExampleMain](../examples/extensions/src/main/java/io/github/sjacek/asyncwright/examples/ExampleMain.java).
Its execution steps are:

1. `AsyncApiParser.parse` performs built-in parsing and validation.
2. `ExtensionExecutor.validate(parsed, rules)` runs project rules, or
   `generate(parsed, rules, generators, outputDirectory)` runs rules followed by generators.
3. Each rule/generator is loaded by name, instantiated and called on the current thread in registration order.
4. The supplied loader is temporarily the thread context loader, including construction,
   and the previous loader is restored after success or failure.

This execution host does not create Gradle workers, tasks or a consumer DSL. A separate jar
compiled against public API types is tested with a supplied `URLClassLoader`; this does not
establish isolation from conflicting Gradle dependencies. That verification belongs to F2.

## Input and parameter ownership

The typed model retains `$ref` fields. Follow a reference using its **document position**,
such as `/operations/send/channel`, rather than the literal contents of `$ref`.
`resolve(pointer)` returns the target pointer/tree; `resolve(pointer, Channel.class)` returns
a typed target. Source and target trees are defensive copies.

Each invocation receives a fresh typed model reconstructed from the validated source projection
(including the agreed handling of ignored reference siblings). Changes to that model stay local
to the invocation; later extensions and the original parsed document remain unchanged.
Reconstruction avoids changes to numeric representations introduced by a model serialization round-trip.
The source and reference index continue to describe the original document.

Parameters contain text values only. The specification copies the map, which remains immutable
in the context. Use `requireParameter(name)` for required values and
`parameters().getOrDefault(name, defaultValue)` for optional values. Missing required parameters
are configuration/execution failures, not invalid-contract diagnostics.

## Diagnostics and stages

Use `context.diagnostic(ruleId, severity, message, definitionPointer)` to preserve source
positions and automatically include related reference usages. Shared definition diagnostics
are deduplicated when identical, including related locations. To report a bad reference, use
its `$ref` position. Files must be project-relative with `/` separators, positions one-based,
and pointers empty for the document root or starting with `/`. The host validates these
requirements for extension diagnostics and their related locations.

- Built-in errors skip all extension loading and execution.
- Built-in warnings permit project rules and generators.
- All project rules run, even after a project error, so their diagnostics can be collected.
- Any parser/rule error prevents all generator loading and execution.
- Generator errors stop later generators. Warnings permit later generators.
- An empty list means no diagnostics; returning null is an extension failure.

Diagnostics are deduplicated and ordered by file, line, column, rule ID and message using
the same ordering as parser results. `ExtensionResult.diagnosticsJson()` serializes diagnostics
only; output paths are available separately through `generatedFiles()`.

Exceptions from loading, constructors, parameter lookup, callback execution and output I/O
produce `ExtensionExecutionException` with the extension class, RULE/GENERATOR kind and
original cause. The host does not print or swallow them, or turn them into an ordinary contract error.
Fatal JVM errors are not intercepted.

## Generated files

Use `context.output().writeUtf8(relativePath, text)` or `write(relativePath, bytes)`.
The helper accepts nested relative paths and normalizes separators to `/` in the result.
It rejects absolute paths (including Windows rooted/drive/UNC forms), parent traversal,
empty paths, and symbolic output links. Existing files may be replaced on a later execution,
but the same normalized path may only be written once in one execution. Case aliases count
as a collision on every host, so two generators cannot silently overwrite each other's output.

No output directory is created when parsing or project validation fails. If a generator
fails after writing files, those files may remain; reported error results include already
written paths. Staging, stale-output cleanup and failing the Gradle task belong to the F2 host.

Extensions are trusted Java code. These checks apply to writes through `GeneratedFiles`;
they do not sandbox arbitrary Java filesystem access. The host must supply a dedicated
output directory, and generators must put their outputs there.

## F2 boundary

P6's `asyncwrightExtensions` configuration, project/included-build resolution, worker
classpath (`API/parser + extension dependencies`), `@Classpath` input tracking,
configuration/build cache integration, generator tasks and TestKit checks remain F2 work.
`buildSrc` is outside the agreed 0.1 extension mechanism. The parser/API and development
example module do not publish artifacts or modify consumer builds.

The previous pre-release `parser.ResolvedReference` type now lives in `api.ResolvedReference`.
Update explicit imports when using that record; resolver lookup behavior is unchanged.
