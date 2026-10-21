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

No official AsyncAPI JSON Schema has been bundled yet. Before F1, record its selected
release tag, commit SHA, license and applicable NOTICE content here, together with any
notices required by dependencies packaged in the eventual plugin distribution.
