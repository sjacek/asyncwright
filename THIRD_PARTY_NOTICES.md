# Third-party notices

The project is MIT licensed. F0 resolves, without modifying or embedding source code:

- `com.asyncapi:asyncapi-core:1.0.0-RC4`, Apache-2.0;
  upstream: https://github.com/asyncapi/jasyncapi.
- Jackson 2.22.4 by default, Apache-2.0;
  upstream: https://github.com/FasterXML/jackson.
- Current transitive runtime dependencies are listed in `docs/f0/dependencies-jackson-2.22.4.txt`.
  Earlier reports for Jackson 2.17.0 and 2.18.2 are retained as historical experiment results.

The Gradle Wrapper scripts and jar originate from Gradle, Apache-2.0:
https://github.com/gradle/gradle. They were copied unchanged from the workspace's
ShardPilot project; distribution checksum verification is configured in
`gradle/wrapper/gradle-wrapper.properties`.

The parser dependency baseline uses:

- `io.github.sjacek.jasyncapi:asyncapi-core:1.0.0-RC4-asyncwright.2`, Apache-2.0;
  fork: https://github.com/sjacek/jasyncapi, model source commit
  `05116f0d99f9e3f3c1b7d1aa8491f120364acf91`, based on upstream RC4 at
  `ecef261084596f6a2e6cb436b03a68c1f905ed6f`. The separate distribution retains the
  upstream LICENSE and authorship. Its jar also embeds upstream's NOTICE from
  `master` at `6c2a325f0826f26cfe26003af208ce15e5e9bbbe` (the RC4 tag had no NOTICE),
  with fork provenance. Local `.1` was superseded by `.2` to add that notice.
- `com.networknt:json-schema-validator:2.0.8`, Apache-2.0;
  upstream: https://github.com/networknt/json-schema-validator/tree/2.0.8.
- `org.jruby.joni:joni:2.2.6`, MIT, for ECMAScript regular expressions;
  upstream: https://github.com/jruby/joni. Its transitive `jcodings` dependency is also MIT.
- Jackson aligned with the `2.22.4` BOM, Apache-2.0.

## Bundled AsyncAPI schema

`parser/src/main/resources/schemas/asyncapi/3.0.0.json` is an unmodified copy of the
official bundled schema with `$id` values from `asyncapi/spec-json-schemas`:

- Release tag: **v6.11.1** (the schema package release, not the AsyncAPI document version).
- Commit: **e609fc2341007395d75df5756fc6fccf662c2087**.
- Document version: **AsyncAPI 3.0.0**, JSON Schema draft-07.
- Source: https://github.com/asyncapi/spec-json-schemas/blob/e609fc2341007395d75df5756fc6fccf662c2087/schemas/3.0.0.json.
- SHA-256: `d4571a420e6ffb7fcc7066c95a6db1202f299a3c51daa103d0706bf30f95e626`.
- License: Apache-2.0. The upstream LICENSE and NOTICE are included unchanged under
  `parser/src/main/resources/META-INF/licenses/spec-json-schemas/` and in the parser jar.

The upstream NOTICE states:

```text
Copyright 2016-2025 AsyncAPI Initiative

This product includes software developed at
AsyncAPI Initiative (http://www.asyncapi.com/).
```

Dependency notices for the eventual plugin distribution must also cover its packaged
transitive dependencies. Preparing the dependency baseline does not publish a plugin or fork artifact.

## Public test corpus

The 12 YAML files in `parser/src/test/resources/conformance/official/` are unmodified
examples from `asyncapi/spec`, release tag `v3.0.0`, commit
`d78dcea70c9b094a3df72f9a4e811828cec778cc`:
https://github.com/asyncapi/spec/tree/d78dcea70c9b094a3df72f9a4e811828cec778cc/examples.
They remain Apache-2.0 licensed. That directory includes the original LICENSE and NOTICE
from the same commit. The corpus manifest records per-file SHA-256 checksums, source
paths and upstream Git blob hashes. Test fixtures are not included in the parser jar.
Synthetic fixtures are authored for Asyncwright and covered by this project's MIT license.

The optional development comparison resolves `@asyncapi/parser:3.6.6` (Apache-2.0),
https://github.com/asyncapi/parser-js. Its dependency tree is pinned by the separate
`tools/conformance/package-lock.json`; it is not a runtime dependency of the Java parser.
