import argparse
import gzip
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile

from PySquashfsImage import SquashFsImage

PROJECT = Path(__file__).resolve().parents[1]
BUILD = PROJECT.parent / "app" / "build" / "linux-runtime"
ALPINE = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/netboot-3.24.4/"
ROOTFS = "alpine-minirootfs-3.24.4-aarch64.tar.gz"
ROOTFS_HASH = "4b14c18f7e90a063b61462ea1462a5732c154bf6adad594974fe6487cd98a8de"
RELEASE = "https://github.com/rhn-1k/QubeVM/releases/download/7.1.5/"
BACKEND_APK = "qube-arm-q11.1.3-android-arm64-v8a-release.apk"
BACKEND_HASH = "ecb2e6ac3025a2a40572bd49a16c48df6d479eb4f092caec199df58328af7ae2"
BOOT_FILES = {
    "vmlinuz-virt": "cd05fed9e617b5df93dc62ece9b78c422fd94e9b432a2f7c092714b139d5eef4",
    "initramfs-virt": "189402937646965374ef2df5d37f76467135179ae168c88175df2fe3e264a0b0",
    "modloop-virt": "a726889467d1d4fefa4ba1ed710f28b9db1fbd019ca3eba2d4c6c65b33536343",
}
LIBRARIES = (
    "libcompat-qube.so", "libcompat-musl.so", "libepoxy.so", "libglib-2.0.so",
    "libpixman-1.so", "libqemu-system-aarch64.so", "libvirglrenderer.so",
)


def download(base, name, expected):
    target = BUILD / "downloads" / name
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
        return target
    temporary = target.with_suffix(".partial")
    try:
        with urllib.request.urlopen(base + name, timeout=60) as response, temporary.open("wb") as out:
            shutil.copyfileobj(response, out)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != expected:
            raise RuntimeError(f"Checksum mismatch: {name}")
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
    return target


def cpio_entry(out, inode, name, mode, content):
    path = name.encode() + b"\0"
    fields = (inode, mode, 0, 0, 1, 0, len(content), 0, 0, 0, 0, len(path), 0)
    header = b"070701" + "".join(f"{value:08x}" for value in fields).encode()
    out.write(header + path + bytes(-(len(header) + len(path)) % 4))
    out.write(content + bytes(-len(content) % 4))


def guest_modules(modloop):
    with SquashFsImage.from_file(str(modloop)) as image:
        base = "/modules/6.18.60-0-virt/"
        files = {entry.path: entry for entry in image}
        deps = files[base + "modules.dep"].read_text()
        dependencies = dict((name, modules.split()) for name, _, modules in (line.partition(":") for line in deps.splitlines()))
        ordered = {}

        def add(name):
            if name in ordered:
                return
            for dependency in dependencies[name]:
                add(dependency)
            ordered[name] = files[base + name].read_bytes()

        add("kernel/fs/ext4/ext4.ko")
        add("kernel/fs/9p/9p.ko")
        add("kernel/net/9p/9pnet_virtio.ko")
        return list(ordered.items())


def prepare(assets_parent, jni_parent):
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or Path.home() / "Library/Android/sdk")
    ndk = Path(os.environ.get("ANDMX_RUNTIME_NDK") or sdk / "ndk/28.2.13676358")
    clang = next(ndk.glob("toolchains/llvm/prebuilt/*/bin/aarch64-linux-android26-clang"))
    libraries = jni_parent / "arm64-v8a"
    assets = assets_parent / "linux-runtime"
    libraries.mkdir(parents=True, exist_ok=True)
    assets.mkdir(parents=True, exist_ok=True)
    backend = download(RELEASE, BACKEND_APK, BACKEND_HASH)
    with zipfile.ZipFile(backend) as archive:
        for library in LIBRARIES:
            (libraries / library).write_bytes(archive.read(f"lib/arm64-v8a/{library}"))
        (assets / "keymaps").mkdir(exist_ok=True)
        (assets / "keymaps/en-us").write_bytes(archive.read("assets/roms/keymaps/en-us"))
    subprocess.run([str(clang), "-Os", "-fPIE", "-pie", "-Wl,-rpath,$ORIGIN",
                    str(PROJECT / "native/launcher.c"), "-ldl", "-o", str(libraries / "liblinux_runtime.so")], check=True)
    boot = {name: download(ALPINE, name, checksum) for name, checksum in BOOT_FILES.items()}
    rootfs = download(ALPINE.rsplit("/", 2)[0] + "/", ROOTFS, ROOTFS_HASH)
    guest_agent = BUILD / "guest-agent"
    subprocess.run(["go", "build", "-trimpath", "-buildvcs=false", "-ldflags=-s -w",
                    "-o", str(guest_agent), str(PROJECT / "tools/guest-agent.go")],
                   env={**os.environ, "GOOS": "linux", "GOARCH": "arm64", "CGO_ENABLED": "0"}, check=True)
    shutil.copyfile(boot["vmlinuz-virt"], assets / "vmlinuz")
    shutil.copyfile(boot["initramfs-virt"], assets / "initramfs")
    with (assets / "initramfs").open("ab") as out:
        out.write(bytes(-out.tell() % 4))
        cpio_entry(out, 1, "init", 0o100755, (PROJECT / "tools/guest-bootstrap").read_bytes())
        cpio_entry(out, 2, "runtime-init", 0o100755, (PROJECT / "tools/guest-init").read_bytes())
        cpio_entry(out, 3, "runtime-modules", 0o40755, b"")
        for i, (name, content) in enumerate(guest_modules(boot["modloop-virt"])):
            cpio_entry(out, i + 4, f"runtime-modules/{i:02}-{Path(name).name}", 0o100644, content)
        cpio_entry(out, 100, "runtime-agent", 0o100755, guest_agent.read_bytes())
        cpio_entry(out, 101, "payload.tgz", 0o100644, rootfs.read_bytes())
        cpio_entry(out, 0, "TRAILER!!!", 0, b"")
    digest = hashlib.sha256()
    for file in ("vmlinuz", "initramfs"):
        digest.update((assets / file).read_bytes())
    (assets / "boot.version").write_text(digest.hexdigest() + "\n")
    disk = BUILD / "disk.empty"
    with disk.open("wb") as out:
        out.truncate(512 * 1024 * 1024)
    try:
        mke2fs = shutil.which("mke2fs") or sdk / "platform-tools/mke2fs"
        subprocess.run([str(mke2fs), "-F", "-q", "-t", "ext4", str(disk)], check=True)
        with disk.open("rb") as source, gzip.open(assets / "disk.template", "wb") as out:
            shutil.copyfileobj(source, out)
    finally:
        disk.unlink()
    print(f"Prepared ARM64 backend and Alpine boot assets in {BUILD}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets", type=Path, default=BUILD / "assets")
    parser.add_argument("--jni", type=Path, default=BUILD / "jni")
    args = parser.parse_args()
    prepare(args.assets, args.jni)
