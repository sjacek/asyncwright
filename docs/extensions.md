# Extension API (0.1)

Extensions implement contracts in `io.github.sjacek.asyncwright.api`. They depend on the
parser/API artifact and its model/Jackson dependencies, without Gradle types. The
`extension-examples` development module demonstrates that dependency and is checked by
the normal build. It is separate from the two production modules.

| Contract | Purpose |
| --- | --- |
| `AsyncApiRule` | `validate(ExtensionContext)` returns project diagnostics. |
| `AsyncApiGenerator` | `generate(GeneratorContext)` writes outputs and returns diagnostics. |
| `BuiltinRuleId` | Stable IDs for built-in diagnostics; `.id()` returns the existing wire value. Diagnostic factories accept the enum or a custom string ID. |
| `AsyncApi30` | Pinned version/schema metadata and the supported `SchemaFormat` enum. |
| `AsyncApiFields` | Exact AsyncAPI/JSON Schema field names, also usable in annotations and switch labels. |
| `JsonPointers` | `escape` and `append` construct document pointers; URI fragment encoding is a separate concern. |
| `ExtensionSpec` | Binary class name and an immutable `Map<String, String>` of parameters. |
| `ExtensionContext` | Document view, text parameters, required-parameter lookup and diagnostic factory. |
| `GeneratorContext` | The same input plus `GeneratedFiles` output access. |
| `AsyncApiDocumentView` | Typed model, source copy, positions and reference access. |
| `AsyncApiReferences` | Target lookup, typed lookup and related reference usage locations. |
| `ResolvedReference` | Target pointer and a defensive copy of its source value. |
| `BooleanSchema` | An `AsyncAPISchema` subtype representing a boolean schema, serialized as `true` or `false`. |
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

This library execution host does not create Gradle workers, tasks or a consumer DSL.
A separate JAR compiled against public API types is tested with a supplied `URLClassLoader`.
The [Gradle host](gradle-plugin.md) additionally verifies worker isolation through TestKit.

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
Schema `const`, `default` and `examples` values use the same instance-data mapping as the
parser and typed resolver, including arbitrary objects and precise JSON numbers.

For a uniform typed view of schema leaves, use `references().resolve(pointer, AsyncAPISchema.class)`.
Boolean targets return `BooleanSchema`; inspect its `value()` before object-schema getters.
You can also resolve a known boolean target directly as `BooleanSchema.class`. A target
of `false` is present, not an empty optional. The model retains reference objects and does
not replace them with targets.

In model fields typed as `Object`, the fork may already represent a boolean schema as a
native `Boolean` (for example, `properties` or `additionalProperties`); that behavior is retained.
Fields requiring `AsyncAPISchema` use the subtype. Both serialize to the same boolean leaf.
Inherited object-schema setters do not change a `BooleanSchema` leaf's `value()` or JSON.
Boolean values inside instance data are ordinary Java booleans, not `BooleanSchema` objects.
An implicit format wrapper gains the default `schemaFormat` only in the mapping projection
and typed model; the source tree and source positions remain unchanged.

Parameters contain text values only. The specification copies the map, which remains immutable
in the context. Use `requireParameter(name)` for required values and
`parameters().getOrDefault(name, defaultValue)` for optional values. Missing required parameters
are configuration/execution failures, not invalid-contract diagnostics.

## Binding-map references

This support is available in the published `0.1.1` / model `.4` pair.
Earlier `0.1.0` / model `.3` do not support these maps. Existing `Map<String, Object>` getters
retain their signatures. An owner's whole-map reference is represented as
`Map.of("$ref", originalUri)`; concrete maps hold protocol names and typed protocol bindings.
In `components.*Bindings`, each name holds a protocol map or a model `Reference` alias.
This corrects the previous flat interpretation of component entries.

The reference map belongs to the current invocation just like the rest of its typed model.
Clearing or changing it in a rule does not alter the original source, the resolver or the
fresh maps supplied to the next generator. Ignored siblings remain visible in `source()`
and produce a warning; they are excluded from the typed reference map and reference graph.

For example, a channel can declare:

```yaml
bindings:
  $ref: '#/components/channelBindings/Alias'
```

with reusable definitions under the document's `components`:

```yaml
channelBindings:
  Shared:
    amqp:
      is: routingKey
      exchange: {name: events, type: topic}
      bindingVersion: 0.3.0
  Alias:
    $ref: '#/components/channelBindings/Shared'
```

This complete generator resolves the owner's map position, then its protocol:

```java
import com.asyncapi.bindings.amqp.v0._3_0.channel.AMQPChannelBinding;
import io.github.sjacek.asyncwright.api.AsyncApiGenerator;
import io.github.sjacek.asyncwright.api.Diagnostic;
import io.github.sjacek.asyncwright.api.GeneratorContext;

import java.util.List;
import java.util.Objects;

public final class ExchangeNameGenerator implements AsyncApiGenerator {
    @Override
    public List<Diagnostic> generate(GeneratorContext context) throws Exception {
        var references = context.document().references();
        var channel = references.resolve("/channels/events").orElseThrow();
        var map = references.resolve(channel.pointer() + "/bindings").orElseThrow();
        var binding = references.resolve(map.pointer() + "/amqp", AMQPChannelBinding.class).orElseThrow();
        var exchange = Objects.requireNonNull(binding.getExchange(), "Expected an AMQP exchange");
        String name = Objects.requireNonNull(exchange.getName(), "Expected an exchange name");
        context.output().writeUtf8("exchange.txt", name);
        return List.of();
    }
}
```

The same pattern applies to server, operation and message maps with their protocol types.
Resolving the map first handles both inline definitions and alias chains. Mapping does not
dereference URIs, fetch resources or merge traits. Missing targets, wrong map kinds and pure
alias cycles remain validation errors. Tests exercise this flow through isolated Gradle
workers, configuration-cache reuse and build-cache restoration.

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
the same ordering as parser results, with pointer, severity and related locations breaking ties.
`ExtensionResult.diagnosticsJson()` serializes diagnostics
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
written paths. The [Gradle host](gradle-plugin.md) adds staging, stale-output cleanup and
task failure handling; direct library callers provide their own lifecycle management.

Extensions are trusted Java code. These checks apply to writes through `GeneratedFiles`;
they do not sandbox arbitrary Java filesystem access. The host must supply a dedicated
output directory, and generators must put their outputs there.

## Gradle host

P6's `asyncwrightExtensions` configuration, project/included-build resolution, worker
classpath (`API/parser + extension dependencies`), `@Classpath` input tracking,
configuration/build cache integration, generator tasks and TestKit checks are implemented
in the published Gradle plugin. See the [DSL and task documentation](gradle-plugin.md).
`buildSrc` is outside the agreed 0.1 extension mechanism. The parser/API and development
example module do not publish artifacts or modify consumer builds.

The previous pre-release `parser.ResolvedReference` type now lives in `api.ResolvedReference`.
Update explicit imports when using that record; resolver lookup behavior is unchanged.
