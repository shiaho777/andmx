# Third-party components

This inventory records what is known about the signed Lite preview source and binary. Rights clearance and outstanding asset checks are tracked in the [preview release runbook](docs/preview-release.md).

| Component | Use in AndMX | Terms and source |
| --- | --- | --- |
| ZCode | Runtime conventions, interoperability, UI and behavior reference. The imported model rule catalog identifies v3.17.2. | Apache-2.0 at both [v3.17.2](https://github.com/zai-org/ZCode/blob/v3.17.2/LICENSE) and [v3.18.2](https://github.com/zai-org/ZCode/blob/v3.18.2/LICENSE). [License text](licenses/ZCode-Apache-2.0.txt) · [AndMX comparison records](ZCODE_PARITY.md) |
| whisper.cpp and ggml | Native on-device speech processing. The model is obtained on demand. | MIT. [Vendored license](app/src/main/cpp/whisper.cpp/LICENSE) · [Source revision and file scope](app/src/main/cpp/whisper.cpp/ANDMX_SOURCE.txt) |
| KaTeX 0.16.11 | Offline formula rendering and associated fonts. | MIT. [License text](licenses/KaTeX-MIT.txt) · [Upstream release](https://github.com/KaTeX/KaTeX/tree/v0.16.11) |
| Mermaid | Offline diagrams. The precise revision of the bundled minified file is pending confirmation. | MIT upstream. [License text](licenses/Mermaid-MIT.txt) · [Upstream repository](https://github.com/mermaid-js/mermaid) |
| AndroidX, Jetpack Compose, Material Components, Hilt and Dagger | Android UI, navigation, persistence and dependency injection. | Apache-2.0. [AndroidX](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt) · [Dagger](https://github.com/google/dagger/blob/master/LICENSE.txt) |
| Kotlin, coroutines and serialization | Language runtime, concurrency and serialization. | Apache-2.0. [Kotlin](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt) · [Coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt) · [Serialization](https://github.com/Kotlin/kotlinx.serialization/blob/master/LICENSE.txt) |

The repository also contains Termux-built PRoot and talloc binaries for existing non-preview builds. The signed Lite preview excludes these binaries. Their redistribution and corresponding-source review is tracked independently. The experimental VM uses a separately versioned [Android Linux Runtime](https://github.com/shiaho777/android-linux-runtime) and is outside the Lite preview artifact.

Direct dependency declarations and version pins are in [the app build](app/build.gradle.kts) and [the Gradle catalog](gradle/libs.versions.toml).
