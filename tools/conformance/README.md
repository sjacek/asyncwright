# Public parser comparison

The public corpus is checked in under `parser/src/test/resources/conformance/`.
It contains **154 cases**: 12 unchanged examples from `asyncapi/spec` tag `v3.0.0`
(commit `d78dcea70c9b094a3df72f9a4e811828cec778cc`) and 142 synthetic fixtures.
The official files retain their original Apache-2.0 LICENSE and NOTICE; synthetic files
use the project MIT license. No application contracts are included.

The manifest records content hashes and separate expected results for both parsers.
The JVM checks also verify upstream Git blob hashes, diagnostic locations, absence of a
partial model on failure, and that every corpus file is accounted for. The external schema
helper is covered by its own checksum. Fixture bytes are preserved by `.gitattributes`.

## IDE analysis of fixtures

Synthetic fixtures deliberately include duplicate keys, malformed syntax, missing fields
and unresolved references. Some valid encoded pointers also produce false positives in IDE
resolvers. Keep these inputs unchanged: applying schema or reference quick-fixes can remove
the condition a regression is meant to exercise and invalidate its checksum.

The binding-schema reference fixtures can also report "Validates to more than one variant".
The pinned upstream schema uses overlapping Reference/Schema branches under `oneOf`.
The IDE validates against that original schema, while the development parser applies nine
explicit corrections in memory through `SchemaCompatibility`. The `*-reference` cases
are positive regressions; `*-wrong-kind` cases must reach the resolver and report
`reference.type`. Keep both sets and the upstream schema bytes unchanged.

For affected data files in IntelliJ IDEA, set the file's analysis level to **None** via
the editor's inspection widget or **Code | Analyze Code | Configure Current File Analysis**.
This is a local preference in the Git-ignored `.idea` directory; do not disable analysis
for Java test code. The Gradle corpus checks still validate every fixture and its outcome.

## Run

Configure the local model repository as described in the root README. From the project root:

```powershell
.\gradlew.bat check assemble --configuration-cache-problems=fail
```

This includes `:parser:publicCorpusTest`, with 154 case checks and one manifest integrity check.
It needs no Node.js or external application contracts. With Gradle dependencies already
cached, add `--offline`. The JVM report is `parser/build/reports/conformance/asyncwright.json`.

For the optional JavaScript comparison, from this directory:

```powershell
npm ci --ignore-scripts --no-audit --no-fund
npm run compare
```

The install needs the npm registry or a populated npm cache. The comparison itself blocks
HTTP, HTTPS and fetch, and resolves only the checked-in local helper. It was verified with
Node.js 26.4.0 and `@asyncapi/parser` 3.6.6; `package-lock.json` pins transitive dependencies.
Core rules are enabled and recommended style rules are disabled. Informational diagnostics,
including the recommendation to upgrade 3.0.0 to 3.1.0, do not count as validation failures.

The default output is `parser/build/reports/conformance/comparison.json`. To refresh the
versioned report after running the JVM checks:

```powershell
npm run compare -- --output ../../docs/conformance/comparison.json
```

`--jvm <file>` selects another JVM report. `--probe` records JavaScript results without a
JVM comparison for investigation. The default mode rejects missing or stale JVM reports,
changed fixture/helper bytes and unreviewed changes in acceptance or error rule sets.
Runtime exceptions fail the command; they are never treated as document rejections.

## Interpretation

The comparison checks document acceptance and rejection, not diagnostic text equality or
model graph equality. JavaScript dereferences and composes a document; Asyncwright retains
`$ref` values and exposes a separate resolver. Two rejecting parsers can report different
diagnostics because Asyncwright stops after structural errors.

The development result is **109 agreements and 45 reviewed differences** across **154 cases**:

| Classification | Cases | Meaning |
| --- | ---: | --- |
| Agreement | 109 | Both accept or both reject. |
| Unsupported | 11 | Traits (including a shared definition), YAML aliases, external files, versions 2.6/3.1, component Avro format, foreign schema dialect and schema resource ID. |
| Model limit | 2 | Unknown `nullable` keyword and custom binding extensions cannot be preserved by the typed Java model. |
| Reference parser difference | 27 | The original 11 differences plus 14 wrong-kind schema references in protocol bindings and two invalid component names accepted by JavaScript. |
| Policy difference | 5 | Asyncwright rejects pure schema and binding-map alias cycles that JavaScript accepts. |
| Implementation gap | 0 | The three identified semantic validation gaps below are fixed. |

Fixed F1 regression cases, rejected by both parsers:

- `redundant-channel-parameter`: the parameter key must occur in the channel address.
- `root-operation-component-channel`: a root operation must refer to a root channel.
- `root-channel-component-server`: a root channel must refer to root servers.

These constraints are in the [Channel Object](https://github.com/asyncapi/spec/blob/d78dcea70c9b094a3df72f9a4e811828cec778cc/spec/asyncapi.md#channelObject),
[Parameters Object](https://github.com/asyncapi/spec/blob/d78dcea70c9b094a3df72f9a4e811828cec778cc/spec/asyncapi.md#parametersObject)
and [Operation Object](https://github.com/asyncapi/spec/blob/d78dcea70c9b094a3df72f9a4e811828cec778cc/spec/asyncapi.md#operationObject).
The manifest now expects rejection with `channel.parameter`, `operation.channel` and
`channel.server`, respectively. Ten additional cases cover matching parameters, allowed
component references, root aliases, percent-encoded tokens, later entries in server lists
and disallowed component aliases whose final resolved body is at the root.

Location restrictions check the first validated reference target before following aliases.
A root operation may reference `/channels/events` when that channel is an alias to a
component. It may not reference `/components/channels/alias` even if that alias eventually
resolves to a root channel. The corresponding server rule follows the same principle.
Definitions physically located in components retain the broader reference locations
allowed by the specification. These checks preserve the model and its `$ref` values.

`openImplementationGaps: 0` counts the manifest's differential implementation-gap category.
It does not include bugs shared by both parsers. The former `schema-boolean` shared limit
has been fixed in Asyncwright: the original fixture is unchanged, but the JVM now accepts it.
It is a valid case with a reviewed JS difference; `knownSharedLimitations` is now **0**.

Ten further boolean cases cover direct payloads, nested keywords, schema aliases, explicit
and implicit default-format wrappers, dependencies, instance data and negative guards.
The JS parser rejects `schema-boolean`, `boolean-payload`, `boolean-schema-reference` and
`boolean-dependency-reference`, although their boolean schemas are valid. Both parsers accept
the nested and wrapper cases. Comparison remains about acceptance, not JS model fidelity.

The P3/P5 review adds ten public cases covering component format wrappers and nested
references, arbitrary schema instance objects, foreign dialects/resource IDs, shared
definition diagnostics and unresolved channel messages. `schema-instance-values` is a
reviewed JS difference: a `$ref` in a const instance is data, but JS tries to resolve it.
See the [validation contract](../../docs/validation.md) for all capability limits and stages.

All 12 official examples pass JavaScript. Asyncwright accepts 10 and rejects the two
streetlights examples because they use traits. The fixtures are intentionally unchanged.
The `example-instance-not-validated` case documents that neither parser validates example
payload instances here. This corpus is a selected regression baseline, not a complete
AsyncAPI conformance suite or a replacement for payload instance validation.

## Binding-map regression coverage (released 0.1.1 baseline)

The 28 new fixtures cover four binding-map kinds, each with direct references, two-hop
aliases, missing targets, wrong target kinds, alias cycles, malformed pointers and invalid
protocol definitions. All eight positive cases are accepted by both parsers. Of the 20
negative cases, both reject 12; eight have reviewed differences: four cycles, three
cross-kind references and one unknown AMQP server binding field. Channel/operation/message
protocol errors reject during schema validation; the server field reaches typed mapping.

The existing 80 cases and all upstream fixture bytes are unchanged. Java regression tests
also check typed protocol values, retained reference URIs, resolver targets, definition/use
positions, independent extension models and Gradle worker/cache behavior. Matching document
acceptance alone would not detect a lost or eagerly expanded model reference.

Additional JVM regressions exercise percent-encoded component names, escaped JSON Pointer
tokens, ignored reference siblings, self-cycles, wrong-kind component aliases and external map references.
They also mutate a rule's owner and component maps to verify that later generators receive
fresh models and that the source-backed resolver stays intact. These cases supplement
the unchanged 108-case cross-parser baseline.

Full-parser positive tests use component names permitted by the specification. Escaped
slashes, tildes, spaces and plus signs are checked at the resolver level without asserting
document validity. Released 0.2.0 enforces component-name syntax; escaped
tokens in schema property names remain legal. The [validation contract](../../docs/validation.md)
describes the changes released in 0.2.0 and the earlier 0.1.1 behavior.

## Protocol-schema regressions released in 0.2.0

The 0.2.0 corpus adds 46 fixtures: local schema references, concrete recursion and
wrong-kind targets at all 14 protocol/owner/field combinations, MQTT scalar alternatives,
and component names. The original 108 fixtures and their outcomes remain unchanged.
The 154-case comparison has 109 agreements and 45 reviewed differences. The 16 new
differences are invalid schema target kinds and invalid component names accepted by JS
core validation. No new implementation-gap or known-shared-limitation category is introduced.
This is the first parser/DSL stage, not acceptance of the entire Kafka/Avro profile.

The AMQP server negative case is a reference-parser difference, not an additional Java
model limitation: [AMQP binding 0.3.0](https://github.com/asyncapi/bindings/blob/master/amqp/README.md#server-binding-object)
requires the server binding to be empty. Asyncwright rejects the unexpected property during
typed mapping; the pinned JS core validation accepts this fixture.
