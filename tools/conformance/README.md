# Public parser comparison

The public corpus is checked in under `parser/src/test/resources/conformance/`.
It contains **60 cases**: 12 unchanged examples from `asyncapi/spec` tag `v3.0.0`
(commit `d78dcea70c9b094a3df72f9a4e811828cec778cc`) and 48 synthetic fixtures.
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

For affected data files in IntelliJ IDEA, set the file's analysis level to **None** via
the editor's inspection widget or **Code | Analyze Code | Configure Current File Analysis**.
This is a local preference in the Git-ignored `.idea` directory; do not disable analysis
for Java test code. The Gradle corpus checks still validate every fixture and its outcome.

## Run

Configure the local model repository as described in the root README. From the project root:

```powershell
.\gradlew.bat check assemble --configuration-cache-problems=fail
```

This includes `:parser:publicCorpusTest`, with 60 case checks and one manifest integrity check.
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

The current result is **48 agreements and 12 reviewed differences**:

| Classification | Cases | Meaning |
| --- | ---: | --- |
| Agreement | 48 | Both accept or both reject. |
| Unsupported | 7 | Traits (two official cases and one synthetic), YAML aliases, external files, versions 2.6 and 3.1. |
| Model limit | 2 | Unknown `nullable` keyword and custom binding extensions cannot be preserved by the typed Java model. |
| Reference parser difference | 2 | JavaScript follows a reference-like extension value and skips the channel parameter check when `parameters` is absent. |
| Policy difference | 1 | Asyncwright rejects a pure schema reference cycle that JavaScript accepts. |
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

No known implementation gaps remain in this selected corpus. The capability/model limits
and the remaining differences below still prevent a claim of complete F1 conformance.

All 12 official examples pass JavaScript. Asyncwright accepts 10 and rejects the two
streetlights examples because they use traits. The fixtures are intentionally unchanged.
The `example-instance-not-validated` case documents that neither parser validates example
payload instances here. This corpus is a selected regression baseline, not a complete
AsyncAPI conformance suite or a replacement for payload instance validation.
