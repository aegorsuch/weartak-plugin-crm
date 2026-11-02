# WearTAK Plugin App Template

`weartak-plugin-app-template` is a Wear OS plugin app for WearTAK. It includes the same combined
plugin behavior as the template repo, but packages it with a visible launcher activity so the
plugin can be exercised directly on the watch.

## Purpose and Capabilities

<a id="what-it-includes"></a>

### What it includes

- hosted settings support
- CEP rule add/remove behavior
- map marker and map-layer actions
- alert requests with toast fallback
- hello-world toast behavior
- a launcher `MainActivity`
- a return-to-WearTAK action
- a bound WearTAK plugin service
- repo-local SDK artifacts under `app/libs/`

This repo is intended for testing the plugin behavior in an app shell with on-device controls.

## Status

This repo is intended for testing plugin behavior in an app shell with on-device controls.
Release readiness, deployment history, and a supported WearTAK version matrix are
undocumented. Build and publishing workflows below describe automation, not release
certification.

## Point of Contacts

Maintainer and support contacts are undocumented in this repository.

## Ports Required

No plugin-owned TCP or UDP listener or Internet permission is declared in the current
source and manifest. Host communication uses Android Binder through the exported plugin
service, protected by the signature-level `com.weartak.plugin.permission.BIND_PLUGIN`
permission. WearTAK host network requirements are undocumented here.

Build and publishing automation needs outbound HTTPS access to configured dependency
repositories, Gradle distribution services, TAK infrastructure, and GitHub; these are
development/CI requirements, not plugin runtime listener ports.

## Equipment Required

- A Wear OS watch declaring `android.hardware.type.watch`, running Android API 30 or later.
- A compatible WearTAK host for exercising host-dependent actions. Host binding requires
  signing compatible with the manifest's signature-level permission; a launcher alone
  does not establish a host connection.
- For development, an Android SDK with platform 34 and JDK 17; the repository includes
  the Gradle wrapper.

## Equipment Supported

The manifest requires watch hardware; the app targets API 34 with minimum API 30.
Tested watch models, emulator configurations, and firmware versions are undocumented.
These manifest constraints are not a device certification list.

## Compilation

<a id="build-and-test"></a>

### Build and test

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The project expects a local Android SDK and JDK 17.

On Windows, use `.\gradlew.bat :app:assembleDebug` and
`.\gradlew.bat :app:testDebugUnitTest`.

## Developer Notes

### README standard for derived plugins

When using this repository as a scaffold, retain these H2 categories in this order:
Purpose and Capabilities; Status; Point of Contacts; Ports Required; Equipment Required;
Equipment Supported; Compilation; Developer Notes; License. Replace template descriptions
with verified project facts, keep project-specific build/testing/publishing and security
details as subsections, and preserve links and anchors. Mark unknown facts as undocumented;
use N/A only when genuinely inapplicable. Derived plugins should declare
`GPL-3.0-or-later` for original project code by default and include the complete GPLv3
text in `LICENSE`. Preserve existing notices and third-party terms; verify ownership
and dependency licensing before publishing rather than relicensing third-party code.

<a id="project-layout"></a>

### Project layout

This repository uses the conventional Android application structure:

- `build.gradle.kts` — project-level plugin declarations
- `settings.gradle.kts` — project settings and `:app` module inclusion
- `app/build.gradle.kts` — Android application configuration, dependencies, and build types
- `app/src/` — plugin source, manifest, resources, assets, and tests
- `app/libs/` — bundled WearTAK SDK AAR artifacts

<a id="forge-release-pipeline"></a>

### Forge release pipeline

`.gitlab-ci.yml` runs publisher/checklist tests and unsigned debug tests for merge requests,
the default branch, and version tags. Protected default-branch pushes also build a
production-signed APK, verify its WearTAK production certificate, and publish it to a
GitHub rolling release. A verified publish dispatches the GitHub workflow, which creates
one release-testing issue per GitLab pipeline.

Before enabling protected default-branch publishing, a project owner must configure these
GitLab CI/CD variables:

| Variable | Value |
| --- | --- |
| `WEARTAK_RELEASE_KEYSTORE_B64` | Base64-encoded WearTAK production keystore |
| `RELEASE_STORE_PASSWORD` | Production keystore password |
| `RELEASE_KEY_ALIAS` | Production key alias |
| `RELEASE_KEY_PASSWORD` | Production key password |
| `GITHUB_RELEASE_REPOSITORY` | GitHub destination as `owner/repository` |
| `GITHUB_RELEASE_TOKEN` | Masked, protected GitHub token with Contents read/write and repository-dispatch access |
| `GITHUB_RELEASE_TAG` | Existing fixed tag on the GitHub destination; publisher never creates or moves tags |
| `GITHUB_RELEASE_APPLICATION_ID` | `applicationId` from `app/build.gradle.kts` |
| `GITHUB_RELEASE_ASSET_PREFIX` | Plugin name only, e.g. `Sextant`, `LACE`, `TAK-CAD`, `goTenna`, or `MOPP`; omit `WearTAK-Plugin-` |

Protect the GitLab default branch and the fixed GitHub release tag. The GitHub repository
must define `RELEASE_TESTER` as a username with permission to be assigned issues and
`GITHUB_RELEASE_TAG` with the same tag value used by GitLab for manual checklist runs.
Keep signing and GitHub tokens in protected/masked CI variables; never commit them.

New plugin authors must set the APK application ID, choose a unique asset prefix, configure
the GitHub mirror/release variables above, and provide production signing variables before
enabling publish jobs. `settings.gradle.kts` uses TAK Artifactory when token-service
credentials are available and falls back to Google Maven Central and the Gradle plugin
portal for local builds.

Published APK filenames follow `WearTAK-Plugin-<PluginName>-<version>-p<pipeline>.apk`,
for example `WearTAK-Plugin-Sextant-5.8.4-p42.apk`. The pipeline is the GitLab
project pipeline IID, not the global pipeline ID. An existing `-p<pipeline>` suffix
is included only once and must match the publishing pipeline; `+` build metadata
is omitted from filenames. Android version metadata, application labels, package IDs,
signed APK bytes, and release tags remain unchanged.

The publisher verifies uploaded bytes before publishing the release and retains existing
assets. For naming migrations, upload the new filename alongside the old asset, verify its
SHA256, then update download links and checksum filename references. Renaming an existing
GitHub asset changes its download URL; it does not preserve old filename links.

<a id="notes"></a>

### Notes

- Debug builds use the standard Android debug keystore when no custom signing values are supplied.
- GitLab or local custom signing can be supplied with the `WEARTAK_DEBUG_*` and `WEARTAK_RELEASE_*`
  environment variables. A root-level `signing.properties` file using the corresponding short
  property names is supported for local builds and must not be committed.
- The WearTAK SDK is consumed from the repo-local `app/libs/` directory.
- Keystore directories are intentionally not included in this template. Shared signing backups
  are retained centrally under `WT-Plugin-Docs/signing-backups/`.
- The previously referenced `docs/Docs.md` is absent from this checkout. Current behavior
  can be inspected in
  [`TemplatePluginRuntime.kt`](app/src/main/java/com/weartak/plugin/template/service/TemplatePluginRuntime.kt)
  and
  [`TemplatePluginBinderTest.kt`](app/src/test/java/com/weartak/plugin/template/service/TemplatePluginBinderTest.kt).

## License

Original project code is licensed under the GNU General Public License, version 3
or (at your option) any later version (`GPL-3.0-or-later`). See [LICENSE](LICENSE)
for the complete GPLv3 text.

This declaration covers the repository owner's original code only, not third-party
code or bundled artifacts. Existing notices and third-party licensing obligations
remain in effect. In particular, `gradlew` and `gradlew.bat` retain their Apache-2.0
copyright and license notices; Gradle wrapper components are not relicensed here.
The bundled WearTAK SDK AARs in `app/libs/` contain no license notice identified during
inspection, so their licensing and redistribution permissions remain unverified.
Other dependencies retain their respective licenses. Verify those terms and preserve
required notices before redistributing source or APKs; this GPL declaration does not
grant rights to third-party components.
