# Preparing and publishing a release

Publication is prepared, but no Asyncwright or fork artifact has been submitted by this work.
The build prepares the first stable plugin candidate `0.1.0`; this is not a released version.
The model candidate is `1.0.0-RC4-asyncwright.3`; `.2` remains an unchanged local baseline.

## Repositories and coordinates

| Artifact | Coordinates | Destination |
| --- | --- | --- |
| Model parent POM | `io.github.sjacek.jasyncapi:asyncapi:1.0.0-RC4-asyncwright.3` | Maven Central |
| Model JAR, sources and Javadoc | `io.github.sjacek.jasyncapi:asyncapi-core:1.0.0-RC4-asyncwright.3` | Maven Central |
| Plugin implementation, sources and Javadoc | `io.github.sjacek.asyncwright:asyncwright-gradle-plugin:0.1.0` | Gradle Plugin Portal |
| Plugin marker POM | `io.github.sjacek.asyncwright:io.github.sjacek.asyncwright.gradle.plugin:0.1.0` | Gradle Plugin Portal |

The parser/API ships inside the plugin JAR, sources and Javadoc. No separate `parser`
publication or application repository is needed. The model retains Apache-2.0 and its
upstream attribution; Asyncwright retains MIT and the bundled schema's separate notices.

## Prepare without uploading

In the model distribution checkout, run `tools/prepare-release.ps1 -Unsigned` with JDK 21
and a Maven Toolchains entry. Its `central-release` profile uses Central Publishing Maven
Plugin 0.12.0, with `central.skipUpload=true` explicitly enforced by the preparation script.
It cleans old staging/signatures, runs tests and produces `target/central-publishing/central-bundle.zip`, containing the
parent/core POMs, binary, sources, Javadoc and checksums. The unsigned bundle is for
verification only. Run `python tools/verify-release-bundle.py --allow-unsigned` to inspect it.
Without `-Unsigned`, the script uses the maintainer's configured GPG key and still skips upload.
The model's manual preparation workflow likewise creates an unsigned bundle without credentials.

Install the tested model into an explicit development repository using the model's
`tools/install-local.ps1`, then configure `asyncwrightModelRepository` as described in the
root README. In Asyncwright, run:

```powershell
.\gradlew.bat check assemble --configuration-cache-problems=fail
```

The test task stages both Maven publications under `gradle-plugin/build/staging-repository`.
This is a local build output, never a remote deployment or Maven Local installation.
`PublishedConsumerTest` serves this repository and the model repository over loopback HTTP.
It copies the real `examples/published-consumer` project to a different directory and uses
a cold TestKit home without `withPluginClasspath`, `includeBuild`, `files(...)` or Maven Local.
It checks marker/implementation/model/parent requests, extension compilation, typed model use,
validation, generation, configuration-cache reuse, error reporting and output cleanup.
Another test inspects source/Javadoc JARs and license entries. These tests need HTTP access
to Maven Central for third-party dependencies and must not be run with Gradle's `--offline`.

For CI preparation, push the distribution changes first. Compatibility builds verify that
the selected model POM version matches the version catalog and record its resolved commit.
Set repository variable `ASYNCWRIGHT_MODEL_REF` to the full distribution SHA after pushing;
the development fallback is `build/asyncwright-distribution`. This fallback is not a release pin.
The eight matrix cells run at most three at a time to limit simultaneous cold Central downloads.
The manual **Prepare release (no upload)** workflow requires a full 40-character model SHA,
runs the reusable eight-pair matrix and uploads only a publication preview as a CI artifact.
There are no publishing credentials or remote publishing steps in either preparation workflow.
GitHub requires a manually triggered workflow to exist on the repository's default branch;
until that setup is in place, use the local preparation commands. See
[GitHub's manual workflow instructions](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow).

## Publish later, after explicit maintainer authorization

1. Review the prepared changes in both repositories and keep `.idea` changes out of their
   commits. The distribution checkout has its own branch, `build/asyncwright-distribution`;
   Asyncwright uses `feature/f2-gradle-plugin`. Commit/push and tag creation belong to the maintainer.
2. Configure and verify the `io.github.sjacek.jasyncapi` namespace in Central and the
   `io.github.sjacek.asyncwright` plugin ID in the Plugin Portal. Store the Central token in
   private Maven settings (`central` server), and configure the real GPG release key, published
   public key and passphrase outside version control. Never release the disposable test key/bundle.
3. Build and inspect the model from the exact intended distribution commit. Run all model
   tests and verify the signed bundle/checksums with the real release key. The optional
   `--gpg` argument to `verify-release-bundle.py` also verifies every detached signature.
4. Only when upload is authorized, run Maven in the model checkout with the `central-release`
   profile (`clean deploy`) and **explicitly** set `-Dcentral.skipUpload=false`. Quote the entire `-D...` argument
   in PowerShell. Central `autoPublish=false` leaves the validated deployment for manual release
   in the Central Portal. Even this upload is an external action; preparation does not perform it.
5. Release the model in Central and wait until its parent POM, core POM and JAR resolve from
   Maven Central. Namespace approval, real-key trust, Portal validation and public propagation
   are external prerequisites that local preparation cannot establish.
6. Review Asyncwright's candidate version `0.1.0` in the root build, update the changelog
   when the release occurs, and run the complete compatibility matrix and HTTP consumer tests
   for that exact candidate with the pinned model SHA. Do not infer a version from a tag.
7. Create the matching stable tag `v0.1.0`. `verifyReleaseTag -PreleaseTag=v0.1.0` rejects
   snapshots, malformed tags and version mismatches. `verifyReleaseModel` also requires the
   selected parent/core artifacts to exist in Maven Central before `publishPlugins` can proceed.
8. Configure `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET` privately. After authorization,
   use `:gradle-plugin:publishPlugins --validate-only -PreleaseTag=v0.1.0` for Portal validation,
   and then `:gradle-plugin:publishPlugins -PreleaseTag=v0.1.0` for submission. Do not pass secrets
   on the command line. Initial Portal approval may still be required. Neither command was run
   during preparation; the build contains no automatic tag-triggered publication.
9. Once the Portal makes the version available, copy `examples/published-consumer` outside the
   checkout and run `gradle check asyncApiGenerateEventsCatalog` using Gradle 9.0+ on a supported
   JDK. Use a fresh Gradle user home and no repository URL overrides. The checked-in defaults
   resolve the marker/plugin through the Portal and the model through Maven Central. Review the
   reports and `build/generated/asyncapi/events/catalog/channels.txt` before recording F2 release acceptance.

The HTTP test proves artifact packaging and dependency resolution before publication. It does
not prove that Central or the Plugin Portal has accepted or exposed the unpublished coordinates.
Do not overwrite a published version; fix an already published artifact in a new version.

Official references: [Gradle plugin publishing](https://docs.gradle.org/current/userguide/publishing_gradle_plugins.html),
[Central publishing and skipUpload](https://central.sonatype.org/publish/publish-portal-maven/).
