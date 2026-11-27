# Privacy and permissions

App settings → **Privacy and permissions** describes data destinations and shows Android authorization status. The core workbench works without an AndMX account.

| Action | Data and destination |
| --- | --- |
| Try the offline sample | The script and sample file stay in AndMX’s app-private storage. There are no model requests, Android permission prompts, or workbench history entries for the sample. |
| Try your file | Android grants access to the selected UTF-8 file. AndMX keeps a private draft and sends its text and your editing instruction to the selected model endpoint after you confirm. The provider processes requests under its own terms. You can save an approved result to a new file through Android’s document picker. |
| Work in a project | AndMX stores conversation and approval history on the device. Normal tasks may send messages, attachments, selected file contents, or tool results to your configured model endpoint. Connections to remote workspaces use the remote hosts you configure. |
| Use MCP, a plugin, or the browser | MCP servers receive arguments for their tools. Browsing connects to the sites you visit. Browser or plugin output used in a model task may be included in model requests. You can disable servers and plugins in Settings. |
| Check for updates | The GitHub release channels connect to GitHub for release metadata and, when you request a download, an APK. These requests use no model credentials. Turn off automatic checks in **App updates**. |
| Report an issue | AndMX shows diagnostic text before you copy it. The text includes the app version, package, build, Android version and API level. You decide what to post on GitHub. |

The first-task drafts live in Android’s no-backup storage. Importing a different file replaces the previous draft for that experience. The normal workbench database and model-settings file are excluded from Android backup and device transfer. Other app data follows the app’s [backup rules](app/src/main/res/xml/data_extraction_rules.xml). Removing app data removes private drafts and local history. Exported files remain at their selected destination.

| Permission | Why it may be requested |
| --- | --- |
| Workspace file access | Direct access to shared-storage project paths. The first-task document picker grants access to the selected file without broad storage permission. |
| Microphone | On-device voice transcription. You can continue by typing. |
| Accessibility and screen capture | Android Use can interact with on-screen UI and capture the screen after Android authorization. Captured images can become task/tool output. Android lets you revoke accessibility access and stop screen sharing. |
| Notifications | Task completion and foreground activity notices. |
| App installation | Android’s installer handles signed app updates and project APK installation. It requires user interaction and, when applicable, permission for this source. |

[中文快速开始](README.zh-CN.md) · [Report a privacy issue](https://github.com/shiaho777/andmx/issues)
