#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 4 || $# -gt 5 ]]; then
    echo "Usage: $0 VERSION_NAME VERSION_CODE SIGNING_KEYSTORE OUTPUT_DIR [EXPECTED_CERTIFICATE_SHA256]" >&2
    exit 2
fi

version_name=$1
version_code=$2
keystore=$3
output=$4
expected_cert=${5:-}
if [[ ! "$version_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+-preview\.[0-9]+$ || ! "$version_code" =~ ^[1-9][0-9]*$ || "$version_code" -lt 2 ]]; then
    echo "Expected a preview version name and a version code greater than 1." >&2
    exit 2
fi

root=$(cd "$(dirname "$0")/.." && pwd)
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z "$sdk" ]]; then
    sdk=$(sed -n 's/^sdk\.dir=//p' "$root/local.properties" 2>/dev/null || true)
fi
if [[ -z "$sdk" || ! -f "$keystore" ]]; then
    echo "Set ANDROID_HOME and provide a keystore file." >&2
    exit 2
fi
build_tools="$sdk/build-tools/${ANDMX_PREVIEW_BUILD_TOOLS:-36.0.0}"
for binary in zipalign apksigner aapt; do
    if [[ ! -x "$build_tools/$binary" ]]; then
        echo "Install Android SDK Build-Tools 36.0.0 or set ANDMX_PREVIEW_BUILD_TOOLS." >&2
        exit 2
    fi
done

mkdir -p "$output"
output=$(cd "$output" && pwd)
keystore=$(cd "$(dirname "$keystore")" && pwd)/$(basename "$keystore")
work=$(mktemp -d "$output/.preview.XXXXXX")
trap 'rm -rf "$work"' EXIT

apk_name=andmx-lite-arm64-v8a.apk
revision=$(git -C "$root" rev-parse HEAD)
dirty=false
if [[ -n $(git -C "$root" status --porcelain) ]]; then
    dirty=true
fi
(
    cd "$root"
    ./gradlew :app:assembleLiteRelease --no-daemon \
      -PandmxGithubPreview=true "-PandmxPreviewVersionName=$version_name" \
      "-PandmxPreviewVersionCode=$version_code" -PtestAbi=arm64-v8a </dev/null
)
unsigned="$root/app/build/outputs/apk/lite/release/app-lite-release-unsigned.apk"
"$build_tools/zipalign" -P 16 -f 4 "$unsigned" "$work/aligned.apk"
"$build_tools/apksigner" sign --ks "$keystore" --out "$work/$apk_name" "$work/aligned.apk"
"$build_tools/zipalign" -c -P 16 4 "$work/$apk_name"
"$build_tools/apksigner" verify --print-certs "$work/$apk_name" > "$work/apk-certificates.txt"
"$build_tools/aapt" dump badging "$work/$apk_name" > "$work/apk-metadata.txt"

python3 - "$root" "$work" "$apk_name" "$version_name" "$version_code" "$expected_cert" "$revision" "$dirty" <<'PY'
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
from zipfile import ZipFile

root, directory, apk_name, version, code, expected, revision, dirty = sys.argv[1:]
root = Path(root)
folder = Path(directory)
apk = folder / apk_name
metadata = (folder / "apk-metadata.txt").read_text()
assert re.search(r"^package: name='com\.andmx' versionCode='" + code + r"' versionName='" + re.escape(version) + "'", metadata, re.M)
assert "targetSdkVersion:'34'" in metadata and "native-code: 'arm64-v8a'" in metadata
assert "application-debuggable" not in metadata
certificate, = re.findall(r"certificate SHA-256 digest: ([a-fA-F0-9]{64})", (folder / "apk-certificates.txt").read_text())
certificate = certificate.lower()
if expected and certificate != expected.replace(":", "").lower():
    raise SystemExit("APK signer does not match the expected release fingerprint.")

with ZipFile(apk) as archive:
    libraries = [name for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")]
    assert libraries
    for name in libraries:
        elf = archive.read(name)
        assert name.startswith("lib/arm64-v8a/") and elf.startswith(b"\x7fELF\x02\x01"), name
        offset = struct.unpack_from("<Q", elf, 32)[0]
        entry_size, count = struct.unpack_from("<HH", elf, 54)
        for index in range(count):
            segment = offset + index * entry_size
            fields = struct.unpack_from("<IIQQQQQQ", elf, segment)
            if fields[0] == 1:
                assert fields[-1] >= 16384, name
with apk.open("rb") as source:
    digest = hashlib.sha256()
    for chunk in iter(lambda: source.read(1024 * 1024), b""):
        digest.update(chunk)
sha256 = digest.hexdigest()
manifest = {
    "versionName": version,
    "versionCode": int(code),
    "packageName": "com.andmx",
    "apk": apk_name,
    "sha256": sha256,
    "certificateSha256": certificate,
    "sourceCommit": revision,
    "workingTreeDirty": dirty == "true",
}
(folder / "release-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
(folder / "SHA256SUMS").write_text(f"{sha256}  {apk_name}\n")
with ZipFile(folder / "NOTICES.zip", "w") as notices:
    for path in [root / "LICENSE", root / "NOTICE", root / "THIRD_PARTY.md", *sorted((root / "licenses").glob("*.txt"))]:
        if path.is_file():
            notices.write(path, path.relative_to(root))
    notices.write(root / "app/src/main/cpp/whisper.cpp/LICENSE", "licenses/whisper.cpp-MIT.txt")
PY

cp "$root/CHANGELOG.md" "$work/CHANGELOG.md"
cp "$root/THIRD_PARTY.md" "$work/THIRD_PARTY.md"
for file in "$apk_name" release-manifest.json SHA256SUMS apk-certificates.txt CHANGELOG.md THIRD_PARTY.md NOTICES.zip; do
    mv "$work/$file" "$output/$file"
done
printf 'Verified preview candidate: %s\n' "$output/$apk_name"
