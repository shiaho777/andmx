---
name: andmx-android-dev
description: Build, run, inspect, and lightly automate Android apps with AndMX Android Dev tools (ZCode android-emulator compatible surface).
---

# AndMX Android Dev

Use this skill when the user wants you to create, modify, build, run, debug, screenshot, or inspect an Android app in the desktop Android Emulator or on a USB-connected Android device.

## Tool Names

Native AndMX tools (same names as ZCode android-emulator MCP tools):

- `android_preflight`
- `android_discover_project`
- `android_create_app`
- `android_build_app` / `android_build_and_run`
- `android_list_devices` / `android_list_avds`
- `android_start_emulator` / `android_stop_emulator` / `android_create_avd`
- `android_install_app` / `android_launch_app` / `android_terminate_app`
- `android_open_url` / `android_screenshot` / `android_logs`
- `android_ui_status` / `android_ui_describe` / `android_ui_resolve`
- `android_ui_tap` / `android_ui_swipe` / `android_ui_type_text` / `android_ui_keyevent`

## Default Workflow

1. Call `android_preflight` first.
   - If the required environment is not ready, follow `INSTALL_ENVIRONMENT.md` before continuing. Missing emulator-only checks do not block a selected ready USB device target.
   - Do not accept Android SDK licenses, enter passwords, wipe emulator data, or delete AVDs on the user's behalf. Stop and ask the user.
2. Discover the project with `android_discover_project`.
   - If no Android project exists and the user wants a new app, call `android_create_app`.
   - Prefer editing Kotlin/Compose files directly after project creation.
   - `android_create_app` refuses to overwrite generated files by default; only pass `overwrite: true` after explicit user confirmation.
3. Build and launch with `android_build_and_run`.
   - Pass `module`, `variant`, `applicationId`, `projectDir`, or `serial` when discovery is ambiguous.
   - Use a selected `serial` for a specific USB device or emulator. Use `android_start_emulator` only when a new GUI emulator is needed, then pass its returned `serial` to follow-up tools.
   - Read the returned `output` first for compile errors.
4. Verify the app visually with `android_screenshot` (returns image content when possible).
5. For runtime checks, use `android_open_url`, `android_launch_app`, `android_terminate_app`, and `android_logs`.
6. For UI automation, call `android_ui_status` first.
   - Prefer `android_ui_describe` or `android_ui_resolve` before tapping coordinates.
   - `android_ui_tap` / `android_ui_swipe` / `android_ui_type_text` / `android_ui_keyevent` use ADB/UI Automator based backends.
   - If UI automation is unavailable, continue with build/run/screenshot and say so.

## Tool Notes

- Prefer these tools over raw `adb`/`emulator` unless a tool does not cover the operation.
- `android_build_app` only builds. `android_build_and_run` builds, reuses a ready target or starts a GUI emulator when needed, installs, and launches.
- `android_create_app` generates a minimal Kotlin + Jetpack Compose app (version catalog + counter UI) suitable for model-driven iteration.
- SDK path, default AVD, API level, build-tools, system image variant/ABI, and JDK major come from plugin user config.

## Project Requirements

- `settings.gradle` or `settings.gradle.kts`
- root `build.gradle` or `build.gradle.kts`
- `app/build.gradle` or `app/build.gradle.kts`
- `gradle.properties` with `android.useAndroidX=true`
- `local.properties` with `sdk.dir=<Android SDK path>` when needed
- Gradle wrapper (`gradlew`) when possible

## Build Troubleshooting

- If Gradle reports `android.useAndroidX property is not enabled`, update `gradle.properties`.
- If `android_preflight` reports Gradle not found, follow the quick Gradle fix in `INSTALL_ENVIRONMENT.md`; do not reinstall the Android SDK for that alone.
- If no AVDs but a USB device is ready, pass that device `serial` to target tools.
- If the wrapper is missing, install Gradle and run `gradle wrapper --gradle-version 8.9`, or let `android_build_app` attempt wrapper generation.
- Prefer `default` system images first when downloads time out.

## Extension Point

Backend uses Android SDK tools, ADB/UI Automator, and Gradle — same public surface as ZCode's android-emulator plugin, branded for AndMX.

## Runtime Requirements

AndMX runs primarily **on a real Android phone**. Device tools prefer the **on-device backend**:

| Capability | On-device backend | Desktop/adb backend |
|------------|------------------|---------------------|
| list devices | `serial=local` (this phone) | `adb devices` |
| install / launch / open url | PackageManager + system installer Intent | `adb install` / `am start` |
| screenshot | MediaProjection (`ScreenCaptor`) | `adb exec-out screencap` |
| UI tap/swipe/type/tree | AccessibilityService | `uiautomator` / `input` |
| create/build project | workspace files + gradle if present | same |
| nested emulator | no | desktop `emulator` binary |

Required user grants for full on-device automation:
1. Accessibility → enable AndMX
2. Screen capture / MediaProjection consent
3. Install unknown apps (for APK install)

Always call `android_preflight` first and trust its `capability` / `on-device backend` sections.
