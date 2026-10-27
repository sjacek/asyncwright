# Releases and verification

## Published 0.2.0 release

**Asyncwright 0.2.0** and model **1.0.0-RC4-asyncwright.5** were published on
**2026-10-27** and accepted from the public repositories. No local repository or
sibling checkout is required by consumers.

| Artifact | Coordinates | Repository |
| --- | --- | --- |
| Model parent POM | `io.github.sjacek.jasyncapi:asyncapi:1.0.0-RC4-asyncwright.5` | Maven Central |
| Model JAR, sources and Javadoc | `io.github.sjacek.jasyncapi:asyncapi-core:1.0.0-RC4-asyncwright.5` | Maven Central |
| Plugin implementation, sources and Javadoc | `io.github.sjacek.asyncwright:asyncwright-gradle-plugin:0.2.0` | [Plugin Portal](https://plugins.gradle.org/plugin/io.github.sjacek.asyncwright/0.2.0) |
| Plugin marker POM | `io.github.sjacek.asyncwright:io.github.sjacek.asyncwright.gradle.plugin:0.2.0` | Plugin Portal |

Tag **`v0.2.0`** identifies plugin source commit
`8e8e230b5599e21981383b9e8b2fbd1ef0d9d752`. The model distribution was released
from `e07d9e1ded75711a5180ea435b0ce02c27a89de8` on
`build/asyncwright-distribution` in `sjacek/jasyncapi`; its embedded model-source
provenance identifies `6de0c02e884a9336f727a6cc797ac8eb304c0e87`.
Documentation changes after publication do not move these immutable release references.
The global CI model pin is the distribution SHA, not the model-source SHA.

The Java DTO backend ships inside the plugin binary, sources and Javadoc. There is
no separate parser or generator publication. See the [changelog](../CHANGELOG.md)
for DTO generation, binding-schema fixes and model API changes requiring recompilation.
The model retains Apache-2.0 and upstream attribution; Asyncwright retains MIT and
the bundled schema's separate notices.

Release acceptance verified all 30 model files against the signed bundle, all five
GPG signatures and twenty checksums. The public plugin marker resolves 0.2.0;
implementation, sources, Javadoc, POM and Gradle metadata match the pre-upload manifest.
The final consumer used Gradle 9.8.1 / Temurin 21.0.12.1, initially empty caches,
Plugin Portal and Maven Central. Custom extensions, typed model access, bundled DTO
generation and Java compilation passed. Configuration-cache reuse, expected rule
failure with output cleanup and build-cache restoration also passed.
The source commit passed all eight supported Gradle/JDK combinations in
[CI #40485070270](https://github.com/sjacek/asyncwright/actions/runs/40485070270).

## Earlier published versions

Asyncwright **0.1.1 / model .4** were published on **2026-10-25** and remain unchanged.
Tag `v0.1.1` identifies plugin commit `d0380742d9cfc1c14076d68948252fa1d412520f`;
model distribution `dac5a9703a9a0f82a7d25a2b78789e1d109da41d` contains model sources
`a6ac111a10f6e18fd26c7b4820bf262125054b66`. Earlier `0.1.0 / .3` also remain available;
`v0.1.0` identifies `62608abc66f8c7c3f9f5a5c48ff31f23782cf115`.
Use the matching old version explicitly when reproducing those historical releases.

## Verify the released consumers

The [custom-extension consumer](../examples/published-consumer) defaults to `0.2.0`
and model `.5`. From the repository root:

```powershell
.\gradlew.bat -p examples/published-consumer check asyncApiGenerateEventsCatalog --configuration-cache-problems=fail
.\gradlew.bat -p examples/java-dto-consumer demo --configuration-cache-problems=fail
```

Both examples use public repositories without includeBuild, Maven Local, injected
plugin classpaths or local model paths. Copy them outside the checkout and use an
installed Gradle 9.0+ on a compatible JDK 17+ for an independent check.
The DTO demo prints `3000000000 / ready`. The custom generator writes
`events.created` followed by LF. Repeat the commands to check configuration-cache
reuse and up-to-date outputs. An invalid channel prefix produces
`sample.channel-prefix`; run generation explicitly with `--continue` to verify
cleanup of all requested generators after validation fails.
`-PasyncwrightVersion=...` in the custom-extension example can select another
published plugin version for comparison; no override is required for 0.2.0.

## Build and test the checkout

The source build resolves model `.5` from Maven Central by default. Clear any
machine-specific model override while building against the public release:

```powershell
.\gradlew.bat assemble :extension-examples:runExample '-PasyncwrightModelRepository=' --configuration-cache-problems=fail
.\gradlew.bat -p examples/gradle-consumer check '-PasyncwrightModelRepository=' --configuration-cache-problems=fail
```

The complete `check` includes prepublication HTTP tests, which deliberately require
an explicit model file repository as a fixture. Build/test the separate model
checkout with JDK 21 and Maven Toolchains, then stage it using
`tools/install-local.ps1 -Repository C:/path/to/fresh-model-repository`. Run:

```powershell
.\gradlew.bat check assemble '-PasyncwrightModelRepository=C:/path/to/fresh-model-repository' --configuration-cache-problems=fail
```

The fixture is served over loopback HTTP with plugin staging artifacts. It is a
packaging test, not a consumer requirement or an upload. The tests copy the public
example and use a cold TestKit home without withPluginClasspath or includeBuild.
The optional `:parser:contractCorpusTest` requires the five preserved F1 acceptance
inputs, selected by `-PcontractsDir=C:/path/to/f1-acceptance-corpus`; ordinary check
uses checked-in public and synthetic fixtures.

## Prepare a future version without uploading

Published 0.2.0 / .5 and earlier coordinates are immutable. Never overwrite them
with another candidate or move their release tags.

1. Keep model fixes separate from distribution metadata. Use new development
   branches; source changes, commits, pushes and tags belong to the maintainer.
2. Change model version and provenance before preparing different model bytes.
   With JDK 21 / Maven Toolchains, the model's `tools/prepare-release.ps1 -Unsigned`
   tests and prepares a local preview with upload and Maven Local installation
   disabled. Verify it with `python tools/verify-release-bundle.py --allow-unsigned`.
   It cannot be published without real GPG signatures.
3. Stage the tested model in a fresh explicit file repository and run plugin
   `check assemble`. Review both POMs, licenses, provenance, binary, sources and Javadoc.
4. After commit/push, require the eight-pair Gradle/JDK matrix using the exact new
   distribution SHA. The manual **Prepare release (no upload)** workflow provides
   a publication preview without publication credentials. Update the global
   `ASYNCWRIGHT_MODEL_REF` when integrating the matching model on main; the released
   0.2.0 pin is `e07d9e1ded75711a5180ea435b0ce02c27a89de8`.

## Publish a future version after authorization

Replace `X.Y.Z` below with a new stable version, not an already published release.

1. Integrate reviewed sources and English documentation onto default main; verify
   CI with the matching model pin and the public website/VCS links.
2. Prepare and verify a model bundle signed with the real release key, keeping
   secrets outside the repository. Upload it through Central Portal or explicitly
   enable upload in the model's central-release profile. Wait for parent/core POMs
   and the model JAR to resolve from Maven Central before submitting the plugin.
3. Create/push the matching plugin tag on the reviewed commit. Run
   `:gradle-plugin:publishPlugins --validate-only -PreleaseTag=vX.Y.Z`
   with `-PasyncwrightModelRepository=` for public-model preflight; remove only
   `--validate-only` for the authorized upload. Both invoke verifyReleaseModel.
   verifyReleaseTag checks the stable version string; verify the actual Git tag
   separately. No tag-triggered publication runs automatically.
4. After Portal availability, verify the marker and publication artifacts, then
   run copied public consumers with empty caches, positive/failure/cache scenarios
   and the new version. Update changelogs, dates and example defaults after release.

Official references: [Gradle plugin publishing](https://docs.gradle.org/current/userguide/publishing_gradle_plugins.html),
[Central publishing](https://central.sonatype.org/publish/publish-portal-maven/).
