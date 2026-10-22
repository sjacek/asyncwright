# Validation scope and diagnostics (0.1)

Asyncwright validates a defined subset of AsyncAPI 3.0.0. A passing comparison with another
parser is a regression result, not proof of complete specification conformance.

## P3 capability boundary

| Construct | Current behavior |
| --- | --- |
| One YAML or JSON document | Supported; syntax errors and duplicate keys reject the document. |
| YAML anchors without alias use | Supported as source notation. |
| YAML aliases, including merge aliases | Rejected with `unsupported.yaml-alias` at the alias token and pointer. |
| AsyncAPI versions other than 3.0.0 | Rejected with `unsupported.version`. |
| Local JSON Pointer references and aliases | Supported, including URI percent encoding and `~0`/`~1` escapes. |
| Recursive schemas with concrete schema content | Supported without expanding references in the model. Pure reference cycles reject. |
| External references and named anchors | Outside the resolver scope; rejected, without fetching remote resources. |
| Traits | Rejected with `unsupported.construct`; no trait merge occurs. |
| Default schema format, implicit or explicit | Supported in payloads and component schemas, including references within wrappers. |
| Other `schemaFormat` values | Rejected with `unsupported.construct`, including Avro and explicit `application/schema+json;version=draft-07`. |
| Explicit `$schema` | Only HTTP/HTTPS draft-07 schema URIs, with or without trailing `#`, are accepted. Other dialects reject with `unsupported.schema-dialect`. |
| Schema resource identifiers `$id` | Rejected with `unsupported.schema-id`; resource scopes and named identifiers are not implemented in 0.1. |
| `const`, `default`, `examples` JSON values | Preserved as instance data, including nested objects, arrays, null and reference-like keys. They are not traversed as document schemas/references. |
| Example instances | Their conformance to the payload schema is not checked. |
| Typed-model omissions or changed source values | Rejected with `model.preservation`; model mapping failures use `model.mapping`. No partial document is returned. |

The three explicit default formats are `application/vnd.aai.asyncapi;version=3.0.0`,
`application/vnd.aai.asyncapi+json;version=3.0.0` and
`application/vnd.aai.asyncapi+yaml;version=3.0.0`. The draft-07 **dialect** within the default
format does not imply support for a different Multi Format Schema media type.

These are product limits, not statements that the rejected constructs are invalid AsyncAPI.
They apply to schema/document positions, not arbitrary instance values or extension data.
The parser, typed resolver and extension snapshots share model mapping that preserves
instance objects and arbitrary-precision JSON numbers. This mapping uses local Jackson
mixins; the separately maintained model artifact and its source are unchanged.

## Known coverage and model limits

- Standalone boolean schemas (`true`/`false`) are permitted by the
  [AsyncAPI 3.0 Schema Object specification](https://www.asyncapi.com/docs/reference/specification/v3.0.0#schemaObject).
  The bundled schema and pinned JS parser both reject the corpus case. The manifest marks
  it as a capability case with `knownSharedLimitation`; the comparison reports it separately.
  This remains an F1 conformance blocker even though both parsers agree.
- Custom binding entries such as `bindings.x-custom` are dropped by the selected typed
  model and therefore rejected by the preservation guard. Source data is never silently
  discarded from a successful document.
- Unknown schema keywords such as `nullable` can pass structural validation but fail typed
  mapping. Use the supported draft-07 null union for nullable types. This is a model limit,
  not support for OpenAPI's nullable semantics.

The [recorded comparison](conformance/comparison.json) contains **70 cases: 53 agreements
and 17 reviewed differences**, plus **one known shared limitation** within the agreements.
The differences comprise 11 capability exclusions, 2 model limits, 3 reference parser
differences and 1 pure-cycle policy difference. No unreviewed outcome changes remain.

The JS parser treats a `$ref` inside a `const` instance value as a reference in
`schema-instance-values`; Asyncwright preserves it as data. This classification follows
the instance-value semantics of [JSON Schema validation draft-07](https://json-schema.org/draft-07/draft-handrews-json-schema-validation-01#rfc.section.6.1.3).
It does not add a rule that accepts an otherwise invalid document.

## P5 diagnostic contract

Each diagnostic has `ruleId`, `severity` (`error` or `warning`), `message`, `file`, `line`,
`column`, `pointer` and `related[]`. Each related location has `file`, `line`, `column`
and `pointer`. Files are normalized project-relative paths with `/`; Windows absolute,
drive-relative and parent-traversal input names are rejected on every host. Positions are
one-based; missing source fields fall back to the closest available ancestor position.

Syntax errors identify the parser token. A second YAML document points at that document,
not line 1. An unsupported YAML alias points at its alias token. Other diagnostics normally
point at a property's key. Bad references point at the use's `$ref`; definition errors are
reported once at the definition and include transitive reference usages in `related`.
Distinct errors at the same definition remain distinct diagnostics.

Identical diagnostics are deduplicated. Ordering uses file, line, column, rule ID and
message, with pointer, severity and related locations as deterministic tie breakers.
`ParseResult` and `ExtensionResult` use the same ordering. Their JSON methods serialize
diagnostics only, without the mutable model or absolute checkout paths.

| Stage | Failure behavior |
| --- | --- |
| Syntax / alias boundary | Stop immediately; no structural, reference, semantic or extension work. |
| Version / structural schema | Stop before capability, reference, semantic and project rule execution. |
| Capability checks | Stop before reference/semantic work and typed model construction. |
| Reference validation | Collect reference errors; skip dependent semantic checks while continuing independent rules. An unresolved channel message set suppresses operation membership checks for that channel. |
| Model mapping / preservation | No partial document; mapping reports the first Jackson failure, preservation reports detected source losses. |
| Project rules | Parser errors skip all extensions. Otherwise collect every rule's diagnostics before deciding whether generators can run. |

For parsing, success means no error diagnostic and a complete document. Warnings permit
subsequent stages. A loading/execution exception in a project extension propagates as
`ExtensionExecutionException`; it is not an ordinary contract diagnostic. See the
[extension API](extensions.md) for generator behavior and partial output handling.

## Remaining F1 acceptance work

Boolean schema support needs a parser/model solution or an explicit revision of the F1
acceptance scope. The documented capability/model exclusions need to be included in the
final acceptance record; a green differential test cannot approve them by itself.
The review and local verification do not establish full AsyncAPI conformance.
Worker isolation, Gradle tasks/DSL, TestKit and task-cache behavior remain F2 work.
