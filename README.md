<div align="center">
  <h1>AndMX</h1>
  <p><img src="app/src/main/res/drawable-nodpi/andmx_block_m_mascot.webp" width="104" alt="AndMX Block M icon"></p>
  <p><a href="https://github.com/shiaho777/andmx/actions/workflows/ci.yml"><img src="https://github.com/shiaho777/andmx/actions/workflows/ci.yml/badge.svg?branch=main" alt="View the latest AndMX CI build on main"></a></p>
  <p><strong>An Android coding agent that works directly in your project.</strong></p>
  <p>Connect a model, ask it to read or edit code, and review proposed changes, terminal output, and file diffs in the same task.</p>
  <p><a href="#install-and-get-started">Install and get started</a> · <a href="#screenshots">Screenshots</a> · <a href="README.zh-CN.md">简体中文</a></p>
</div>

## Supported devices

AndMX runs on **Android 8.0 (API 26) and up**. Standard APK builds target **ARM64 (`arm64-v8a`)**. Choose your build before installing.

| Build | Use it for | Execution environment |
| --- | --- | --- |
| **Lite** | Reading and editing project files, Git, and the core agent workflow | Android tools and Kotlin/JGit. Targets API 34. |
| **Proot** | Running a Linux toolchain, shell commands, and builds on the device | A rootless Alpine Linux environment via PRoot. Sideloaded build targeting API 28. The first setup downloads Alpine. |

## Screenshots

These screenshots show the Android 15 build from `main` at [`509d1e2`](https://github.com/shiaho777/andmx/commit/509d1e206c30c6310f7c61dab87fdf45bb19c966). The task uses a demo model and a sample project. Select any image to open it at full size.

**One task, from request to review.** AndMX reads a validation note, asks before editing, runs a verification command after approval, and opens the resulting diff.

<p align="center">
  <a href="static/screenshot/conversation.webp"><img width="232" src="static/screenshot/conversation.webp" alt="Completed AndMX task with messages, a tool group and changed files"></a>
  <a href="static/screenshot/edit-approval.webp"><img width="232" src="static/screenshot/edit-approval.webp" alt="Edit approval with the proposed line change and an Allow once action"></a>
  <a href="static/screenshot/file-diff.webp"><img width="232" src="static/screenshot/file-diff.webp" alt="Applied file diff with line numbers and a dedicated revert action"></a>
</p>

<details>
<summary>New task, project and model pickers, sidebar and task search</summary>

<p align="center">
  <a href="static/screenshot/new-task.webp"><img width="232" src="static/screenshot/new-task.webp" alt="New task with project selection, model controls and tool shortcuts"></a>
  <a href="static/screenshot/project-picker.webp"><img width="232" src="static/screenshot/project-picker.webp" alt="Project picker with local folders and a remote connection entry"></a>
  <a href="static/screenshot/model-menu.webp"><img width="232" src="static/screenshot/model-menu.webp" alt="Model picker with two demo models and a Manage models entry"></a>
  <a href="static/screenshot/sidebar.webp"><img width="232" src="static/screenshot/sidebar.webp" alt="Task sidebar with task history and quick actions"></a>
  <a href="static/screenshot/task-search.webp"><img width="232" src="static/screenshot/task-search.webp" alt="Sidebar search matching text inside a previous task"></a>
  <a href="static/screenshot/message-match.webp"><img width="232" src="static/screenshot/message-match.webp" alt="Search result revealed inside a previously folded process in the conversation"></a>
</p>

</details>

<details>
<summary>Command approval, tool output, revert confirmation, terminal and files</summary>

<p align="center">
  <a href="static/screenshot/command-approval.webp"><img width="232" src="static/screenshot/command-approval.webp" alt="Command approval with its shell command and a one-time permission choice"></a>
  <a href="static/screenshot/tool-output.webp"><img width="232" src="static/screenshot/tool-output.webp" alt="Expanded tool result with command output, elapsed time and exit code"></a>
  <a href="static/screenshot/revert-confirmation.webp"><img width="232" src="static/screenshot/revert-confirmation.webp" alt="Confirmation shown before reverting an applied file edit"></a>
  <a href="static/screenshot/terminal.webp"><img width="232" src="static/screenshot/terminal.webp" alt="Interactive terminal listing files in the sample workspace"></a>
  <a href="static/screenshot/file-browser.webp"><img width="232" src="static/screenshot/file-browser.webp" alt="Workspace file browser showing the sample documentation files"></a>
</p>

</details>

<details>
<summary>Models, command search, browser, settings and dark appearance</summary>

<p align="center">
  <a href="static/screenshot/models.webp"><img width="232" src="static/screenshot/models.webp" alt="Models settings with the configured demo provider"></a>
  <a href="static/screenshot/settings.webp"><img width="232" src="static/screenshot/settings.webp" alt="Settings categories for the agent, extensions and workspace"></a>
  <a href="static/screenshot/command-search.webp"><img width="232" src="static/screenshot/command-search.webp" alt="Command search with actions available from the keyboard or sidebar"></a>
  <a href="static/screenshot/browser.webp"><img width="232" src="static/screenshot/browser.webp" alt="Built-in browser displaying the AndMX README"></a>
  <a href="static/screenshot/dark.webp"><img width="232" src="static/screenshot/dark.webp" alt="AndMX task timeline in dark appearance"></a>
</p>

</details>

## What you can do

| Task | How AndMX helps |
| --- | --- |
| **Work in a project** | Open a local directory, reference files with `@`, browse the workspace, and use file-editing and Git tools. Proot adds an on-device Linux shell and PTY terminal. |
| **Control tool execution** | In **Build** mode, reads run directly while file edits and commands ask for confirmation by default. Inspect the request, allow it once, or manage rules scoped to the task or project. |
| **Review results** | Expand tool cards to inspect inputs and output, including the exit code for shell runs. Open **File changes** for line-by-line diffs and review tracked changes before confirming a revert. |
| **Return to a task** | Search task titles and message text from the sidebar, jump to a match inside a folded process, and pick up where you were reading when switching tasks. |
| **Choose how to connect** | Use a provider or a reachable self-hosted endpoint via OpenAI Chat Completions, OpenAI Responses, or Anthropic Messages. Add tools through MCP, skills, and plugins. Android Use and experimental dynamic workflow tools are available in Settings. |

Track requested features and known issues in the [issue tracker](https://github.com/shiaho777/andmx/issues).

## Install and get started

The repository currently has no published APK on [GitHub Releases](https://github.com/shiaho777/andmx/releases). Build a debug APK from source using **JDK 17**, **Android SDK Platform 34**, **Build-Tools 34.0.0**, **NDK 27.0.12077973**, and **CMake 3.22.1**. Install the SDK tools with Android Studio and set `ANDROID_HOME` or set `sdk.dir` in a local `local.properties`. [Android Studio](https://developer.android.com/studio)

```bash
git clone https://github.com/shiaho777/andmx.git
cd andmx
./gradlew :app:assembleLiteDebug
```

| Build | Gradle task | APK under `app/build/outputs/apk/` |
| --- | --- | --- |
| Lite | `:app:assembleLiteDebug` | `lite/debug/app-lite-debug.apk` |
| Proot | `:app:assembleProotDebug` | `proot/debug/app-proot-debug.apk` |

To install over USB on a device with USB debugging enabled, run `./gradlew :app:installLiteDebug` or `./gradlew :app:installProotDebug`. Both builds use the `com.andmx` package name.

1. Open **Settings → Models → Add provider**. Choose the protocol and enter your provider’s base URL and any required API key. Fetch the model list or enter a model ID manually, then save.
2. In **New task**, select the model and choose a project folder. Android may prompt for all-files access when you open local projects.
3. Start with a small task, for example, “Read `README.md` and identify the local validation steps.” Keep **Build** mode selected if you want to approve edits and commands as they come up.
4. For a task that writes files, inspect the proposed change first. After execution, open **File changes** in the task to review the applied diff.

### What leaves the device

Workspace tools and the Proot runtime execute on the device. Model calls send prompts and the context needed for the task, which can include file excerpts and tool output, to your configured provider. Web and MCP tools can contact their own endpoints. Android Use needs screen-capture and accessibility permission for screen capture and interaction. [App permissions](app/src/main/AndroidManifest.xml)

## Contribute and get help

- Report bugs with the app version, Android version, build variant, steps to reproduce, and expected and actual results. **Settings → About → Copy diagnostics** provides the version and device details. Remove credentials and private content from reports and screenshots. [Report an issue](https://github.com/shiaho777/andmx/issues/new)
- Contributions to Android UI, model adapters, tool behavior, tests, and documentation are welcome. The repository uses Issue-linked PRs into `main` with `CI / build` as its merge gate. [Contribution guide](CONTRIBUTING.md)

<details>
<summary>Source layout and local tests</summary>

| Location | Responsibility |
| --- | --- |
| [`ui2/`](app/src/main/java/com/andmx/ui2) | Native Jetpack Compose workbench, task history, settings and terminal UI |
| [`agent/`](app/src/main/java/com/andmx/agent), [`llm/`](app/src/main/java/com/andmx/llm) | Agent loop, permission policy, tool registry, model adapters and streaming |
| [`exec/`](app/src/main/java/com/andmx/exec), [`cpp/`](app/src/main/cpp) | Process execution, PRoot setup and PTY bridge |
| [`data/`](app/src/main/java/com/andmx/data), [`workspace/`](app/src/main/java/com/andmx/workspace), [`diff/`](app/src/main/java/com/andmx/diff) | Room persistence, project files, change tracking and diff rendering data |
| [`app/src/test/`](app/src/test), [`app/src/androidTest/`](app/src/androidTest) | Unit and instrumentation tests |

```bash
./gradlew :app:testLiteDebugUnitTest
./gradlew :app:connectedLiteDebugAndroidTest
```

For an x86_64 test emulator, add `-PtestAbi=x86_64` to the instrumentation command. [CI workflow](.github/workflows/ci.yml) · [Agent instructions](AGENTS.md)

</details>

## License and credits

The AndMX repository is publicly visible on GitHub and currently declares no project-wide license. Bundled third-party components retain their own terms. The Proot build uses PRoot and libtalloc packages, and the app includes whisper.cpp for local transcription. [PRoot license](https://github.com/termux/proot/blob/master/COPYING) · [libtalloc package and license](https://github.com/termux/termux-packages/blob/master/packages/libtalloc/build.sh) · [whisper.cpp license](app/src/main/cpp/whisper.cpp/LICENSE)
