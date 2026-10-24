# Preparing and publishing a release

## Current status

Asyncwright [0.1.0 is published in the Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.github.sjacek.asyncwright/0.1.0),
and model `1.0.0-RC4-asyncwright.3` is published in Maven Central. Tag `v0.1.0` identifies
source commit `62608abc66f8c7c3f9f5a5c48ff31f23782cf115`; those public artifacts stay unchanged.

This branch prepares **Asyncwright 0.1.1** with stable model
**1.0.0-RC4-asyncwright.4**. Neither candidate has been uploaded or published.
The [changelog](../CHANGELOG.md) describes binding-map references, diagnostics and upgrading.
The model retains source provenance `a6ac111a10f6e18fd26c7b4820bf262125054b66`.

The model distribution branch is `build/binding-map-references-distribution` in
`sjacek/jasyncapi`; the plugin branch is `fix/binding-map-references` in `sjacek/asyncwright`.
The source fix and distribution are separate worktrees of the same model repository.
Keep source fixes separate from Maven distribution metadata. Do not include local `.idea`
changes in release commits. Commits, pushes and release tags belong to the maintainer.

| Prepared artifact | Coordinates | Intended destination |
| --- | --- | --- |
| Model parent POM | `io.github.sjacek.jasyncapi:asyncapi:1.0.0-RC4-asyncwright.4` | Maven Central |
| Model JAR, sources and Javadoc | `io.github.sjacek.jasyncapi:asyncapi-core:1.0.0-RC4-asyncwright.4` | Maven Central |
| Plugin implementation, sources and Javadoc | `io.github.sjacek.asyncwright:asyncwright-gradle-plugin:0.1.1` | Gradle Plugin Portal |
| Plugin marker POM | `io.github.sjacek.asyncwright:io.github.sjacek.asyncwright.gradle.plugin:0.1.1` | Gradle Plugin Portal |

The parser/API ships inside the plugin JAR, sources and Javadoc, without a separate parser
publication. The model retains Apache-2.0 and upstream attribution; Asyncwright retains
MIT and the bundled schema's separate notices.

## Prepare without uploading

1. In the model distribution checkout, configure JDK 21 and a matching Maven Toolchains
   entry. Run `tools/prepare-release.ps1 -Unsigned`, then
   `python tools/verify-release-bundle.py --allow-unsigned`. The preparation script runs
   tests and packages `target/central-publishing/central-bundle.zip` with
   `central.skipUpload=true` and installation into Maven Local disabled. It cleans previous
   staging and signatures. An unsigned bundle is for verification only.
2. Inspect the parent/core POMs, binary, sources, Javadoc, checksums, LICENSE, NOTICE and
   `META-INF/asyncwright-fork.properties`. The version must be `.4`, the source SHA must
   be `a6ac111a10f6e18fd26c7b4820bf262125054b66`, and there must be no working-tree marker.
   A future signed preparation uses the real release key configured outside version control;
   it still does not upload. `--gpg PATH` optionally verifies every detached signature.
3. Install the tested model into a new explicit file repository with
   `tools/install-local.ps1 -SkipBuild -Repository C:/path/to/model-repository`.
   Existing versions with different contents are rejected; do not overwrite an earlier
   local or public artifact. Never use Maven Local as a substitute for the consumer test.
4. In Asyncwright, run:

   ```powershell
   .\gradlew.bat check assemble :extension-examples:runExample :parser:contractCorpusTest `
     verifyReleaseTag '-PreleaseTag=v0.1.1' `
     '-PasyncwrightModelRepository=C:/path/to/model-repository' `
     '-PcontractsDir=C:/path/to/f1-acceptance-corpus' `
     --configuration-cache-problems=fail
   ```

   `verifyReleaseTag` validates the candidate tag string against `project.version`; it does
   not create a Git tag. The optional `contractCorpusTest` uses the five preserved F1
   acceptance inputs (91 local references and 13 typed AMQP channel bindings); select that
   corpus with `contractsDir`. Evolving application contracts can have different totals.
   Do not change the fixed assertions to make a changed input corpus pass. The ordinary
   `check` uses only the checked-in public/synthetic corpus and needs no application contracts.
   Do not run `verifyReleaseModel` or `publishPlugins` during local preparation: the model
   must become publicly available before Portal submission.
5. Review `gradle-plugin/build/staging-repository`: it contains the implementation, marker,
   sources, Javadoc and metadata under stable `0.1.1` coordinates. The test build writes
   only this local output, with no remote deployment. `PublishedConsumerTest` serves it and
   the staged model over loopback HTTP. It copies the real example outside the checkout,
   uses a cold TestKit home, and checks resolution, typed extensions, validation, generation,
   configuration-cache reuse, diagnostics and failure cleanup. It uses no included plugin
   build, `withPluginClasspath`, file dependencies or Maven Local. Tests inspect the source
   and Javadoc artifacts and license entries. HTTP access to third-party dependencies is
   required, so this test cannot run with Gradle's `--offline` option.
6. After committing and pushing the preparation, run CI with the **new full distribution
   SHA** as `model_ref`. The old `5fe89cf4...` commit contains `.4-SNAPSHOT`, so it cannot
   supply stable `.4`. The eight Gradle/JDK combinations must pass on the stable candidates.
   The manual **Prepare release (no upload)** workflow also prepares a publication preview
   after that matrix and has no publishing credentials or remote publication steps.

Do not change the global `ASYNCWRIGHT_MODEL_REF` while `main` still requires `.3`.
Coordinate that variable with integration of the `.4` dependency on `main`, then verify CI
of the actual integrated commit. Keep the immutable source SHA and distribution SHA distinct.
Do not declare the stable candidate remotely tested using an earlier SNAPSHOT run.

## Publish after integration and explicit authorization

1. Bring the reviewed plugin sources, README and linked documentation onto GitHub's default
   `main` branch. Check that `website` and `vcsUrl` open the complete public project, including
   parser/API and Gradle plugin sources. Review the candidate's exact distribution and plugin
   commits and record the matching tags. Local preparation does not merge or tag anything.
2. Verify the Central namespace `io.github.sjacek.jasyncapi`, the real release GPG key and
   public key availability. Keep Central credentials in private Maven settings and signing
   secrets outside the repository. Build and inspect the signed model bundle from the
   intended immutable distribution commit. Never release an unsigned preview or a bundle
   signed with a disposable test key.
3. Only after authorization, invoke Maven with profile `central-release`, `clean deploy`,
   and explicit `'-Dcentral.skipUpload=false'`. Quote each `-D...` argument in PowerShell.
   `autoPublish=false` leaves final release to the maintainer in Central Portal. Wait for
   the parent POM, core POM and JAR of `.4` to resolve from Maven Central.
4. Create the plugin's matching `v0.1.1` tag on the reviewed source commit. With credentials
   configured privately, Portal validation uses
   `:gradle-plugin:publishPlugins --validate-only -PreleaseTag=v0.1.1`; submission uses
   `:gradle-plugin:publishPlugins -PreleaseTag=v0.1.1`. Both depend on `verifyReleaseModel`,
   which checks the model's availability in Maven Central. These commands contact external
   services and are not part of the no-upload preparation. There is no automatic
   tag-triggered publication.
5. When `0.1.1` is available in the Portal, run the copied
   [published-consumer example](../examples/published-consumer) outside the checkout with
   `-PasyncwrightVersion=0.1.1`, a fresh Gradle user home and no repository overrides.
   Check validation, generation, configuration/build caches, diagnostics and cleanup.
   Then update the public quick-start and example defaults from `0.1.0`, replace the
   changelog's prepared status with the actual release date, and record release acceptance.

The public consumer defaults intentionally stay at `0.1.0` / model `.3` until publication is
confirmed. Prepublication HTTP tests override the plugin version to the candidate and serve
the exact `.4` model. They verify packaging and resolution but do not establish acceptance
or availability in Central or the Portal. Never overwrite a published version.

Official references: [Gradle plugin publishing](https://docs.gradle.org/current/userguide/publishing_gradle_plugins.html),
[Central publishing and skipUpload](https://central.sonatype.org/publish/publish-portal-maven/).
