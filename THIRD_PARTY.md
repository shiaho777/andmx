# Third-party components

This inventory records what is known about the signed AndMX release source and binary. Rights clearance and outstanding asset checks are tracked in the [preview release runbook](docs/preview-release.md).

| Component | Use in AndMX | Terms and source |
| --- | --- | --- |
| ZCode | Runtime conventions, interoperability, UI and behavior reference. The imported model rule catalog identifies v3.17.2. | Apache-2.0 at both [v3.17.2](https://github.com/zai-org/ZCode/blob/v3.17.2/LICENSE) and [v3.18.2](https://github.com/zai-org/ZCode/blob/v3.18.2/LICENSE). [License text](licenses/ZCode-Apache-2.0.txt) · [AndMX comparison records](ZCODE_PARITY.md) |
| whisper.cpp and ggml | Native on-device speech processing. The model is obtained on demand. | MIT. [Vendored license](app/src/main/cpp/whisper.cpp/LICENSE) · [Source revision and file scope](app/src/main/cpp/whisper.cpp/ANDMX_SOURCE.txt) |
| KaTeX 0.16.11 | Offline formula rendering and associated fonts. | MIT. [License text](licenses/KaTeX-MIT.txt) · [Upstream release](https://github.com/KaTeX/KaTeX/tree/v0.16.11) |
| Mermaid | Offline diagrams. The precise revision of the bundled minified file is pending confirmation. | MIT upstream. [License text](licenses/Mermaid-MIT.txt) · [Upstream repository](https://github.com/mermaid-js/mermaid) |
| AndroidX, Jetpack Compose, Material Components, Hilt and Dagger | Android UI, navigation, persistence and dependency injection. | Apache-2.0. [AndroidX](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt) · [Dagger](https://github.com/google/dagger/blob/master/LICENSE.txt) |
| Kotlin, coroutines and serialization | Language runtime, concurrency and serialization. | Apache-2.0. [Kotlin](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt) · [Coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt) · [Serialization](https://github.com/Kotlin/kotlinx.serialization/blob/master/LICENSE.txt) |

The APK includes the [Android Linux Runtime integration at revision `b6fffc8`](https://github.com/shiaho777/android-linux-runtime/tree/b6fffc8e7489a369d534e7b2e6502e4bb8479131), with AndMX changes in [linux-runtime](linux-runtime). The [preparation script](linux-runtime/tools/prepare.py) pins the exact engine and Alpine inputs by SHA-256.

| Runtime component | Source and license |
| --- | --- |
| Android Linux Runtime integration | [Original source license](linux-runtime/LICENSE) |
| Android QEMU port and native dependencies | [QubeVM 7.1.5 build recipe, source and license](https://github.com/rhn-1k/QubeVM/tree/a45126697776e1efd4c8d72b94335e88590492ef) |
| QEMU 11.1.3 | [QEMU source and license](https://github.com/qemu/qemu/tree/v11.1.3) |
| Alpine Linux 3.24.4 kernel, initramfs and base system | [Package sources](https://gitlab.alpinelinux.org/alpine/aports/-/tree/v3.24.4) · [Matching ARM64 binaries](https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/) |
| Go standard library in the guest bridge | [Go source and license](https://go.dev/LICENSE) |

Bundle corresponding sources and notices for the pinned engine and Alpine components when distributing the APK.

Direct dependency declarations and version pins are in [the app build](app/build.gradle.kts) and [the Gradle catalog](gradle/libs.versions.toml).
