# Releases and verification

## Prepared 0.2.0 release

Stable **Asyncwright 0.2.0** selects model **1.0.0-RC4-asyncwright.5**.
Both are prepared for verification, not yet published. The Java DTO backend ships
inside the plugin implementation, sources and Javadoc; there is no separate
java-generator or parser publication. See the [changelog](../CHANGELOG.md) for
DTO generation, binding-schema fixes and the model API changes requiring recompilation.

The model keeps immutable source provenance
`6de0c02e884a9336f727a6cc797ac8eb304c0e87`. Distribution packaging changes receive
their own commit; pin that full distribution SHA after the maintainer commits and
pushes it. The previous 0598d83 / 421e78bf compatibility matrix and contracts preview
accepted the snapshot implementation, not the final stable release packaging.

Prepare an unsigned model bundle with its tools/prepare-release.ps1 -Unsigned
script, verify all five artifacts and twenty checksums, then install the tested
model into a fresh file repository. From this plugin checkout:

```powershell
.\gradlew.bat check assemble :gradle-plugin:publishAllPublicationsToStagingRepository verifyReleaseTag `
  '-PasyncwrightModelRepository=C:/path/to/model-repository' `
  '-PreleaseTag=v0.2.0' --configuration-cache-problems=fail
```

The output stays in gradle-plugin/build/staging-repository. check includes a copied
HTTP consumer with cold caches, bundled DTO generation and compilation, failure
cleanup and cache checks. This verifies the prepared 0.2.0 artifacts without Portal
submission. An unsigned model bundle is not suitable for public release.

The public quick start and published-consumer defaults remain at released 0.1.1 / .4.
Release completion requires reviewed integration and CI with the final distribution
SHA, model signatures and Central availability, Portal submission and a public-only
consumer test. Commits, tags, merges and uploads are separate maintainer steps.

Local preparation on 2026-10-27 passed with Gradle 9.8.1 / JDK 21:
clean check assemble and stable-tag verification, 596 cases (595 passed and the
Windows symlink case skipped), no failures or errors. The staged implementation,
marker, metadata, sources and Javadoc use 0.2.0 consistently; all six files and
24 checksums were checked. Model dependency .5 and bundled DTO/API/parser classes
are present, with no separate parser or generator dependency. The HTTP consumer
tests passed, and the standalone DTO demo printed 3000000000 / ready.
This local evidence does not replace CI on the final committed revisions or
the public-only acceptance after publication.

## Published versions

**Asyncwright 0.1.1** and model **1.0.0-RC4-asyncwright.4** were published on
**2026-10-25** and verified from the public repositories.

| Artifact | Coordinates | Repository |
| --- | --- | --- |
| Model parent POM | `io.github.sjacek.jasyncapi:asyncapi:1.0.0-RC4-asyncwright.4` | Maven Central |
| Model JAR, sources and Javadoc | `io.github.sjacek.jasyncapi:asyncapi-core:1.0.0-RC4-asyncwright.4` | Maven Central |
| Plugin implementation, sources and Javadoc | `io.github.sjacek.asyncwright:asyncwright-gradle-plugin:0.1.1` | [Plugin Portal](https://plugins.gradle.org/plugin/io.github.sjacek.asyncwright/0.1.1) |
| Plugin marker POM | `io.github.sjacek.asyncwright:io.github.sjacek.asyncwright.gradle.plugin:0.1.1` | Plugin Portal |

Tag **`v0.1.1`** identifies plugin source commit
`d0380742d9cfc1c14076d68948252fa1d412520f`. Model distribution commit
`dac5a9703a9a0f82a7d25a2b78789e1d109da41d` identifies the released `.4` packaging;
its embedded model-source provenance is `a6ac111a10f6e18fd26c7b4820bf262125054b66`.
Documentation changes after publication do not move those release references.

The parser/API ships inside the plugin JAR, sources and Javadoc, without a separate parser
publication. The model retains Apache-2.0 and upstream attribution; Asyncwright retains
MIT and the bundled schema's separate notices. The [changelog](../CHANGELOG.md) describes
binding-map references and upgrading. Earlier `0.1.0` / model `.3` remain unchanged;
`v0.1.0` identifies `62608abc66f8c7c3f9f5a5c48ff31f23782cf115`.

## Verify the released consumer

The [public consumer](../examples/published-consumer) defaults to `0.1.1` and resolves
model `.4` from Maven Central. From the repository root, run:

```powershell
.\gradlew.bat -p examples/published-consumer check asyncApiGenerateEventsCatalog --configuration-cache-problems=fail
```

It uses no included plugin build, local model repository, Maven Local or injected plugin
classpath. Copy the example outside the checkout to verify the same behavior independently,
using an installed Gradle 9.0+ and a supported JDK 17+. No version override is required.
`-PasyncwrightVersion=...` can explicitly select another published version for comparison.

The release was accepted with Gradle 9.8.1 / Temurin 21.0.12.1, initially empty Gradle and
Maven caches, and public repository defaults. Checks covered extension compilation, typed
model use, validation, generation, configuration-cache reuse, rule failure cleanup and
build-cache restoration. The generated catalog contains `events.created` followed by LF.
Public implementation, sources, Javadoc, POM and Gradle metadata matched the publication
outputs. All five model signatures and 20 checksums were verified from Central.

## Build and test the checkout

The prepared 0.2.0 checkout and its included-build examples need model `.5` in an
explicit file repository until publication. Pass the property below or configure
ignored gradle/local.properties:

```powershell
.\gradlew.bat assemble :extension-examples:runExample '-PasyncwrightModelRepository=C:/path/to/model-repository' --configuration-cache-problems=fail
.\gradlew.bat -p examples/gradle-consumer check '-PasyncwrightModelRepository=C:/path/to/model-repository' --configuration-cache-problems=fail
```

The complete `check` also contains HTTP publication tests. These deliberately need an
explicit model file repository as a fixture, not as a consumer requirement. See the
[build instructions](../README.md#build). The fixture is served over loopback HTTP alongside
`gradle-plugin/build/staging-repository`; it never uploads to a remote repository.
Tests copy the public example to another directory and use a cold TestKit home without
`withPluginClasspath` or `includeBuild`.

The optional `:parser:contractCorpusTest` expects the five preserved F1 acceptance inputs
(91 references and 13 typed AMQP channel bindings). Select them with
`-PcontractsDir=C:/path/to/f1-acceptance-corpus`. Ordinary `check` uses checked-in public and
synthetic fixtures and does not depend on evolving application contracts.

## Prepare a future version without uploading

Published .4 / 0.1.1 remain immutable. The next versions, .5 / 0.2.0, are prepared
above; never overwrite artifacts already published under older coordinates.

1. Keep model fixes separate from distribution metadata. The distribution lives on
   `build/asyncwright-distribution` in `sjacek/jasyncapi`; the released plugin is on `main`
   in `sjacek/asyncwright`. Use development branches for the next changes. Commits, pushes
   and tags belong to the maintainer; keep local `.idea` changes out of them.
2. With JDK 21 and Maven Toolchains, `tools/prepare-release.ps1 -Unsigned` in the model
   checkout runs tests and prepares a local preview with `central.skipUpload=true` and
   Maven Local installation disabled. Inspect it with
   `python tools/verify-release-bundle.py --allow-unsigned`. The unsigned ZIP is only for
   verification. Review parent/core versions, source provenance, source/Javadoc artifacts,
   LICENSE, NOTICE and checksums. Signed preparation uses the real GPG key configured
   outside the repository; it still does not upload. `--gpg PATH` verifies signatures.
3. Stage the tested model in a new explicit file repository with `tools/install-local.ps1`.
   Existing artifacts with different contents are rejected. Configure that repository
   for the full plugin `check assemble` and HTTP publication tests.
4. After the maintainer's commit/push, pin the new full distribution SHA in CI and require
   the complete eight-pair Gradle/JDK matrix. The manual **Prepare release (no upload)**
   workflow also creates a local publication preview and has no publication credentials.
   Coordinate the global `ASYNCWRIGHT_MODEL_REF` with the dependency version on `main`.
   Its current released pin is `dac5a9703a9a0f82a7d25a2b78789e1d109da41d`; update it only
   when integrating a different model revision. Keep source and distribution SHAs distinct.

## Publish a future version after authorization

Replace `X.Y.Z` below with the new stable plugin version. These commands describe a future
release; they are not instructions to republish `0.1.1`.

1. Integrate reviewed sources and public usage documentation onto the default `main`
   branch, then verify its CI with the matching model pin. Check that `website` and
   `vcsUrl` open the complete project. Record the exact source commits and immutable tags.
2. Build and inspect a model bundle signed with the real release key. Keep Central tokens
   in private settings and signing secrets outside version control. For authorized upload,
   the model's `central-release` profile uses `clean deploy` with explicit
   `'-Dcentral.skipUpload=false'`; `autoPublish=false` leaves final release to the maintainer
   in Central Portal. Alternatively, upload the verified signed ZIP manually in Portal.
   Wait for the model parent/core POMs and JAR to resolve from Maven Central.
3. Create the matching plugin tag on the reviewed commit. `verifyReleaseTag` rejects a
   snapshot or a tag string that differs from `project.version`. With credentials configured
   privately, `:gradle-plugin:publishPlugins --validate-only -PreleaseTag=vX.Y.Z` performs
   preflight validation; `:gradle-plugin:publishPlugins -PreleaseTag=vX.Y.Z` submits the
   release. Both depend on `verifyReleaseModel`, which checks the public model. There is
   no automatic tag-triggered publication.
4. After Portal availability, run the copied public consumer with empty caches, public
   repositories and the new version, including positive, failure and cache scenarios.
   Then update the examples, release dates and documentation. A prepublication HTTP test
   proves packaging and resolution, not acceptance or availability in public services.

Official references: [Gradle plugin publishing](https://docs.gradle.org/current/userguide/publishing_gradle_plugins.html),
[Central publishing and skipUpload](https://central.sonatype.org/publish/publish-portal-maven/).
