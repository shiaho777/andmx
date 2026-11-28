# Signed AndMX releases

The GitHub release uses Android package `com.andmx`, ABI `arm64-v8a`, and target SDK 34. Choose the release version and its increasing Android version code together. The Git tag is `v<versionName>`, with `<major>.<minor>.<patch>` for stable builds and `<major>.<minor>.<patch>-preview.<number>` for previews. The ARM64 APK includes Android Linux Runtime and the Alpine base system.

The signed APK is named `andmx.<versionName>.APK`, for example `andmx.1.0.0.APK`. Users with an older updater that expects a fixed filename install the first release with this naming scheme manually.

## Source and asset clearance

- ZCode’s cited v3.17.2 and v3.18.2 tags both contain Apache-2.0 license blob `550d8df4cfc74878663a511caa97852f15fd9592`. The v3.17.2 model rule catalog is documented in AndMX commit `3993973`. Map any reused code and assets to the applicable versions and retain required attribution and modification notices.
- Confirm the source and license of the bundled Mermaid build, earlier UI assets and branding, native artifacts and plugin content. Reconcile the actual transitive dependency graph with the [third-party inventory](../THIRD_PARTY.md). Include corresponding sources and notices for the bundled QEMU and Alpine components.
- Record the maintainer’s source and asset clearance against a commit, then add the agreed Apache-2.0 project license and applicable notices. The upstream ZCode license applies to its covered material. AndMX’s own licensing and use of other assets require their respective authorization.

## Build and verify locally

Add the same version entry to both localized changelogs under `app/src/main/assets/product` and to `CHANGELOG.md`. Build from a clean checkout of the selected commit on `main` after its CI passes. Use the chosen version name and code for both the APK and the release manifest.

Use the standard Android development prerequisites, Python 3.9 or newer, and Android SDK Build-Tools 36.0.0 for alignment verification. Prepare the runtime build dependencies listed in the README. Keep the signing keystore outside the worktree. Obtain the expected production certificate’s SHA-256 fingerprint from the maintainer by an independent channel.

```bash
tools/preview-release.sh "$VERSION_NAME" "$VERSION_CODE" /absolute/path/to/keystore.p12 \
  /absolute/path/to/preview-output "$CERTIFICATE_SHA256"
```

`apksigner` prompts for the keystore password. The script aligns the APK before signing and verifies the APK’s signature, identity, version, ABI and native ELF segment alignment. It writes the APK, checksums, release manifest, certificate details, changelog and third-party notice archive to the output directory. Use a dedicated disposable key for local rehearsal and keep those artifacts out of the public release.

Verify a fresh install and an in-place update on an ARM64 Android 15 (4 KB) emulator, an ARM64 Android 16 (16 KB) emulator, and a physical ARM64 device. For the upgrade rehearsal, sign both APKs with the same test key and increment both version identifiers. Reopen the app to check that Android retains the data and AndMX shows the new version notes once. For a public release, verify the manifest records the approved commit and a clean working tree. Verify that a tampered download and a different signing certificate are rejected before installation. Preserve any existing developer installation and data by using a separate emulator or test profile for the signing exercise.

## Publish after qualification

1. Confirm the production-signed APK passed device verification and the release manifest records the selected commit, version, and signing certificate. Check that the chosen tag is available on GitHub.
2. Create an annotated tag for that exact commit and push it to GitHub. Confirm the remote tag resolves to the same commit recorded in the release manifest.
3. Prepare a GitHub Release draft using the existing tag and matching changelog. Mark it as a prerelease when publishing to the preview channel. Upload `andmx.<versionName>.APK`, `release-manifest.json`, `SHA256SUMS`, `apk-certificates.txt`, and the license and notice files in `NOTICES.zip`. Verify the draft's uploaded APK against the recorded SHA-256 and certificate before publishing.
4. After publication, download the public APK and confirm it still matches the verified digest and signing certificate. Keep the same Android release signing key for subsequent compatible upgrades.

This workflow follows WebToApp's version consistency checks and its use of an annotated tag tied to a separate GitHub Release. WebToApp attaches checksums with Actions after publication. AndMX includes its APK checksum in the draft and rechecks the public download because the app updater consumes `release-manifest.json`. [WebToApp version checklist](https://github.com/shiaho777/web-to-app/blob/main/AGENTS.md#13-bump-the-release-version) · [Example release](https://github.com/shiaho777/web-to-app/releases/tag/v2.9.0) · [Checksum workflow](https://github.com/shiaho777/web-to-app/blob/main/.github/workflows/release-checksums.yml)

On the first run, choose whether AndMX should check GitHub after onboarding. **Settings → App updates** allows manual checking, skipping a version and downloading a signed update. AndMX verifies the APK before opening Android’s installer. GitHub Releases also provides the APK for manual verification and sideloading.

[Android app signing](https://developer.android.com/studio/publish/app-signing) · [Android 16 KB verification](https://developer.android.com/guide/practices/page-sizes) · [GitHub Releases API](https://docs.github.com/en/rest/releases/releases)
