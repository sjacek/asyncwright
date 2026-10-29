# Avro validation (development 0.3.0-SNAPSHOT)

This feature is unreleased. Public Asyncwright 0.2.1 does not support this profile.
The [source-build example](../examples/avro-validation) validates Kafka payload and
key schemas without a broker, Registry, Node.js or a published snapshot.

## Supported schema profile

Use exactly one of these Multi Format Schema media types:

- `application/vnd.apache.avro;version=1.9.0`
- `application/vnd.apache.avro+json;version=1.9.0`
- `application/vnd.apache.avro+yaml;version=1.9.0`

Wrappers are supported in message payloads and `components.schemas`. A Kafka message
`key` can reference a component wrapper; a wrapper directly inside a binding field
remains unsupported. This does not change other protocols' schema-valued fields.

```yaml
components:
  schemas:
    Order:
      schemaFormat: application/vnd.apache.avro;version=1.9.0
      schema:
        type: record
        name: Order
        namespace: example.orders
        fields:
          - {name: id, type: long}
          - {name: note, type: [null, string], default: null}
```

Avro primitive and complex types, depth-first named references, namespaces, recursive
records and field defaults are checked. Each wrapper/file has its own name scope;
one file cannot provide named types to another. Union defaults must match the **first**
branch, as required by the 1.9 profile, even though newer Avro parsers are more permissive.
Default values are schema declarations; this feature does not validate message instances.

Known 1.9 logical types are `decimal`, `uuid`, `date`, `time-millis`, `time-micros`,
`timestamp-millis`, `timestamp-micros` and `duration`. Invalid known annotations produce
errors. This is a deliberate authoring policy, stricter than the specification's reader
fallback to the underlying type. Unknown or post-1.9 logical types produce warnings;
their underlying types are validated and metadata retained, without claiming support
for the newer logical semantics. Arbitrary Avro attributes, aliases, docs and defaults
are retained. A `$ref` inside metadata/default data is not a document reference.

Validation uses Apache Avro 1.12.2 with an explicit 1.9 name/default/logical profile.
The bundled AsyncAPI schema remains unchanged. Its approximation of the Avro grammar
is bypassed only at recognized Avro leaves; the enclosing document/wrapper remains
structurally validated. The typed-model preservation guard still applies.

## Declared local files

A wrapper's `schema` may be a Reference Object to a complete JSON `.avsc` file:

```yaml
schemaFormat: application/vnd.apache.avro;version=1.9.0
schema: {$ref: 'avro/order.avsc'}
```

The path is relative to the main AsyncAPI document's directory. URI percent encoding
is decoded without interpreting `+` as a space. Only regular UTF-8 JSON files declared
as inputs may be read. Normalized paths and real paths must stay inside the project;
absolute paths, URI schemes, queries, fragments, remote files and symlink escapes fail.
There is no transitive file loading from an Avro schema. File references are not general
JSON Schema external references and do not enable network access.

In Gradle, `schemaFiles` defaults to recursive `**/*.avsc` below the document directory:

```groovy
asyncwright.specs {
    orders {
        file.set(layout.projectDirectory.file('contracts/orders.yaml'))
        schemaFiles.setFrom(layout.projectDirectory.file('contracts/avro/order.avsc'))
    }
}
```

`setFrom(...)` replaces that default allowlist; `from(...)` extends it. Use `fileTree`
for directories. Every declared file is tracked, including unreferenced files; the
parser reads only referenced targets. Validation and generation track contents and
complete project-relative names. Changes invalidate both tasks; cache results are
relocatable and work with configuration cache. Input boundaries are checked before
up-to-date/cache reuse. Generator output cannot contain a declared schema input.

## Standalone parser and extension API

Legacy `parse(String, String)` and `parse(Path projectRoot, Path file)` methods validate inline schemas but
never read external schema files. Opt in using an immutable context:

```java
import io.github.sjacek.asyncwright.parser.AsyncApiParser;
import io.github.sjacek.asyncwright.parser.ParseContext;
import io.github.sjacek.asyncwright.parser.ParseResult;
import java.nio.file.Path;
import java.util.Set;

public final class AvroExample {
    public static ParseResult parse(Path projectRoot, String input) {
        ParseContext context = new ParseContext(projectRoot,
                Set.of("contracts/avro/order.avsc"));
        return new AsyncApiParser().parse("contracts/orders.yaml", input, context);
    }
}
```

The additive `parse(Path, ParseContext)` overload checks and reads the main document
within that context's root. Constructing the context performs no filesystem access.

The typed model and source retain the original `$ref`. Rules/generators obtain a
defensive snapshot through `document.references().resolveSchemaFile(pointer)` where
`pointer` identifies the wrapper's `/schema` position. Resolve document aliases to the
wrapper first, then append `/schema`. `ResolvedSchemaFile.file()` is project-relative;
`node()` returns a fresh tree. Reading the snapshot performs no filesystem I/O and
cannot alter another extension's view. Ordinary `resolve(...)` continues to resolve
document references separately.

## Diagnostics and limits

- `avro.schema`: invalid grammar, names, defaults or `.avsc` JSON syntax.
- `avro.logical-type`: invalid known annotation (error) or unknown annotation (warning).
- `avro.reference`: disallowed, undeclared, missing or unreadable file reference.
- `unsupported.reference`: file fragments, or a file reference used through a legacy
  parser without context.
- `model.mapping` / `model.preservation`: an otherwise valid schema cannot be represented
  faithfully by the selected typed model; no partial document is returned.

File schema errors point into the `.avsc` file and include related document usages.
Repeated targets are read and validated once per parse, with all usages attached.
Reference policy failures point to the document's `$ref`. Reports use relative paths.

Avro validation does **not** add Avro DTO generation. The bundled Java DTO generator
reports `java-dto.unsupported` for Avro payloads; it still supports its documented
AsyncAPI/JSON schema profile. This feature also does not add Registry retrieval,
Kafka clients, provisioning, broker tests, traits or other schema-format versions.

See the [Avro 1.9 specification](https://avro.apache.org/docs/1.9.0/spec.html),
[comparison procedure](../tools/conformance/README.md) and
[extension API](extensions.md).
