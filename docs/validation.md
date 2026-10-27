# Validation scope and diagnostics (0.1)

Asyncwright validates a defined subset of AsyncAPI 3.0.0. A passing comparison with another
parser is a regression result, not proof of complete specification conformance.

## Prepared 0.2.0 binding-schema and component-name changes

The development branch adds schema context for Kafka `key` / `groupId` / `clientId`,
HTTP headers and query schemas, WebSockets query/header schemas, JMS and Anypoint MQ
headers, and the five MQTT 0.2.0 schema-valued fields. MQTT scalar alternatives remain
scalars. Schema references are checked for target kind, missing targets and cycles;
concrete recursive schemas are supported and references stay in the model. Arbitrary
binding configuration and schema instance data do not become reference edges.

Nine overlapping Reference/Schema alternatives are corrected from `oneOf` to `anyOf`
in memory. The pinned schema resource and its checksum remain unchanged; tests verify
the complete correction allowlist, scalar constraints and the protocol position registry.

Reusable component keys must match `^[a-zA-Z0-9.\-_]+$`. Invalid keys produce
`component.name` at the key, including its escaped JSON Pointer. This includes binding-map
components and trait component maps; traits themselves remain unsupported. Arbitrary
payload property names and extension data are unaffected.

These changes are **not part of published 0.1.1**. Full Kafka acceptance, Registry
dependencies, Avro, local `.avsc` references and the Kafka example are still pending.

## P3 capability boundary

| Construct | Current behavior |
| --- | --- |
| One YAML or JSON document | Supported; syntax errors and duplicate keys reject the document. |
| YAML anchors without alias use | Supported as source notation. |
| YAML aliases, including merge aliases | Rejected with `unsupported.yaml-alias` at the alias token and pointer. |
| AsyncAPI versions other than 3.0.0 | Rejected with `unsupported.version`. |
| Local JSON Pointer references and aliases | Supported, including URI percent encoding and `~0`/`~1` escapes. |
| Whole binding-map references (since 0.1.1) | Server/channel/operation/message maps and aliases in `components.*Bindings` are supported with published model `.4`; cross-kind targets and alias-only cycles reject. Public `0.1.0` / model `.3` still reject these maps. |
| Recursive schemas with concrete schema content | Supported without expanding references in the model. Pure reference cycles reject. |
| External references and named anchors | Outside the resolver scope; rejected, without fetching remote resources. |
| Traits | Rejected with `unsupported.construct`; no trait merge occurs. |
| Default schema format, implicit or explicit | Supported in payloads and component schemas, including references within wrappers. |
| Boolean schemas `true`/`false` | Supported in components, payloads, headers, nested schema positions and default-format wrappers; boolean reference targets retain their values. |
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
mixins and a boolean-aware schema deserializer. Binding-map support in published `0.1.1`
uses the separately maintained model `.4` from Maven Central. See the
[mapping and resolver example](extensions.md#binding-map-references).

## Boolean schemas

`true` and `false` are schema values under the
[AsyncAPI 3.0 Schema Object specification](https://www.asyncapi.com/docs/reference/specification/v3.0.0#schemaObject).
They stay boolean when serialized; they are not replaced by equivalent object schemas.
The parser does not validate example payload instances, so a valid `false` schema does
not by itself reject an AsyncAPI document.

The typed resolver returns `api.BooleanSchema` for boolean targets requested as
`AsyncAPISchema.class`, including references terminating at `false`. The subtype's `value()`
exposes the leaf. Some untyped model fields already hold native Java `Boolean` values;
the [extension contract](extensions.md) explains this distinction. `const/default/examples`
booleans remain instance data. A boolean outside a schema position does not become a
valid channel, message, operation or security scheme.

The upstream bundle's `anySchema` conditional uses `required: [schema]` without restricting
the instance type, which incorrectly routes a boolean to the object-only wrapper validator.
`SchemaCompatibility` adds `type: object` to that condition in an in-memory copy. The
bundled resource stays byte-identical with its provenance/checksum intact. Tests verify
the single tree change and the continued rejection of invalid object schemas and scalars.

Wrappers without `schemaFormat` select the default AsyncAPI format. Mapping adds that
default to the typed model without changing the source or positions. Boolean dependencies
are indexed as schemas; a reference to an arbitrary boolean instance value is still invalid.

## Known coverage and model limits

- Published 0.1.1 does not enforce the Components Object name pattern.
  Use component keys matching `^[a-zA-Z0-9.\-_]+$`, as required by the
  [Components Object specification](https://www.asyncapi.com/docs/reference/specification/v3.0.0#componentsObject).
  JSON Pointer support for arbitrary escaped tokens does not relax this document rule.
  The development parser closes this gap with `component.name` and dedicated regressions.
- Custom binding entries such as `bindings.x-custom` are dropped by the selected typed
  model and therefore rejected by the preservation guard. Source data is never silently
  discarded from a successful document.
- Unknown schema keywords such as `nullable` can pass structural validation but fail typed
  mapping. Use the supported draft-07 null union for nullable types. This is a model limit,
  not support for OpenAPI's nullable semantics.

The development [recorded comparison](conformance/comparison.json) contains **154 cases: 109 agreements
and 45 reviewed differences**, with **zero known shared limitations** in this selected corpus.
The differences comprise 11 capability exclusions, 2 model limits, 27 reference parser
differences and 5 pure-cycle policy differences. No unreviewed outcome changes remain.

The former `schema-boolean` limitation is now a valid JVM case and a JS difference. The
pinned JS parser rejects it, direct boolean payloads, reference aliases to `false` and a
reference to a boolean dependency. Both parsers accept the nested boolean and format-wrapper cases.

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

## F1 acceptance

F1 was accepted on **2026-10-22** for the parser and plain-Java rule/generator API in
commit `e5b73f957ece1127f1490930b3563ef7ca897328`, within the explicit 0.1 scope above.
The acceptance includes the documented capability exclusions and model limits; it does
not claim complete AsyncAPI 3.0 conformance or a released Gradle plugin.

A clean `check assemble`, example execution and the five corrected external contracts
completed successfully on Gradle **9.8.1 / Temurin 21.0.12.1**. Of **189 test cases**,
**188 passed** and **one was skipped** because this Windows host could not create symbolic
links. That skip affects the output helper's symlink guard verification; all other output
path guards passed. Compilation targets Java 17 (classfile version 61); the full supported
Gradle/JDK runtime matrix belongs to F2.

A fresh comparison with **@asyncapi/parser 3.6.6 / Node 26.4.0** reproduced the committed
report: **80 cases, 59 agreements, 21 reviewed differences, zero unexpected changes**.
The six JVM-accepted/JS-rejected cases are deliberate, documented corrections of reference
parser behavior: reference-like extension/const data and four valid boolean-schema cases.
They do not disable required semantic checks. The comparison is supporting evidence;
specification behavior, negative tests and source preservation remain acceptance criteria.

The external integration check uses the five corrected contract copies and verifies
**91 local references and 13 typed AMQP bindings**. It does not establish acceptance of
the original contracts containing `nullable` or perform application migration.

Worker isolation, Gradle tasks/DSL, TestKit and task-cache behavior were outside the F1
acceptance scope. They are now implemented and documented in the [Gradle plugin guide](gradle-plugin.md).
Public distribution of the model artifact is required before release. The historical
negative F0/P1 result for upstream RC4 remains unchanged.
