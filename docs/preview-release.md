# Signed Lite preview

The first planned GitHub prerelease is `v0.1.0-preview.1`, version code `2`. Its Android package is `com.andmx`, its ABI is `arm64-v8a`, and its target SDK is 34. The PRoot and VM variants use their existing build paths.

## Source and asset clearance

- ZCode’s cited v3.17.2 and v3.18.2 tags both contain Apache-2.0 license blob `550d8df4cfc74878663a511caa97852f15fd9592`. The v3.17.2 model rule catalog is documented in AndMX commit `3993973`. Map any reused code and assets to the applicable versions and retain required attribution and modification notices.
- Confirm the source and license of the bundled Mermaid build, earlier UI assets and branding, native artifacts and plugin content. Reconcile the actual transitive dependency graph with the [third-party inventory](../THIRD_PARTY.md). PRoot and talloc remain in the repository and have separate distribution requirements even though the preview APK excludes them.
- Record the maintainer’s source and asset clearance against a commit, then add the agreed Apache-2.0 project license and applicable notices. The upstream ZCode license applies to its covered material. AndMX’s own licensing and use of other assets require their respective authorization.

## Build and verify locally

Add the version entry to both localized files under `app/src/main/assets/product` and update the project changelog before building.

Use the standard Android development prerequisites, Python 3.9 or newer, and Android SDK Build-Tools 36.0.0 for alignment verification. Keep the signing keystore outside the worktree. Obtain the expected production certificate’s SHA-256 fingerprint from the maintainer by an independent channel.

```bash
tools/preview-release.sh 0.1.0-preview.1 2 /absolute/path/to/keystore.p12 \
  /absolute/path/to/preview-output CERTIFICATE_SHA256
```

`apksigner` prompts for the keystore password. The script aligns the APK before signing and verifies the APK’s signature, identity, version, ABI and native ELF segment alignment. It writes the APK, checksums, release manifest, certificate details, changelog and third-party notice archive to the output directory. Use a dedicated disposable key for local rehearsal and keep those artifacts out of the public release.

Verify a fresh install and an in-place update on an ARM64 Android 15 (4 KB) emulator, an ARM64 Android 16 (16 KB) emulator, and a physical ARM64 device. For the upgrade rehearsal, sign both APKs with the same test key and increment both version identifiers. Reopen the app to check that Android retains the data and AndMX shows the new version notes once. For a public release, verify the manifest records the approved commit and a clean working tree. Verify that a tampered download and a different signing certificate are rejected before installation. Preserve any existing developer installation and data by using a separate emulator or test profile for the signing exercise.

## Publish after qualification

Create a GitHub **prerelease** from the approved source commit with the version tag and changelog. Upload `andmx-lite-arm64-v8a.apk`, `release-manifest.json`, `SHA256SUMS`, `apk-certificates.txt`, and the applicable license and notice files in `NOTICES.zip`. Confirm that GitHub serves the same SHA-256 and certificate fingerprint after publication. Keep the same Android release signing key for subsequent compatible upgrades.

On the first run, choose whether AndMX should check GitHub after onboarding. **Settings → App updates** allows manual checking, skipping a version and downloading a signed update. AndMX verifies the APK before opening Android’s installer. GitHub Releases also provides the APK for manual verification and sideloading.

[Android app signing](https://developer.android.com/studio/publish/app-signing) · [Android 16 KB verification](https://developer.android.com/guide/practices/page-sizes) · [GitHub Releases API](https://docs.github.com/en/rest/releases/releases)
