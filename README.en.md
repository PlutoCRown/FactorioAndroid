# Factorio Android Launcher

[![GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)
![Android ARM64](https://img.shields.io/badge/platform-Android%20ARM64-3DDC84.svg)

[简体中文](README.md) | **English**

Run your own official Linux ARM64 Factorio on Android. This is an unofficial launcher and is not affiliated with Wube Software.

## About

### Introduction

The launcher runs the official Linux ARM64 graphical client through a GNU/Linux runtime, Mesa Zink and the device’s Vulkan driver, presenting its output on an Android Surface. This project does not include or distribute Factorio game files. Download and keep your own legitimate copy from the official site, preferably purchased and downloaded with your own account. The launcher can import, start, and play the game only when you have an official game package.

### Features

- Import your own official Linux ARM64 full graphical client.
- Import ZIP saves and access the active game profile’s save and mod folders through the system file manager.
- Forward keyboard, mouse and touch mouse input, and connect gamepads and Android input methods through SDL3 interfaces.
- Share configuration, saves and mods across all game versions and base game / Space Age packages.

These features depend on the game interfaces and device runtime. Missing native interfaces and runtime failures are reported. Importing a package does not guarantee that release will run on every device.

## How to play

1. Open [Releases](https://github.com/PlutoCRown/FactorioAndroid/releases), download this app, and install it. Choose the standard APK for the launcher release you want.
2. Sign in at [factorio.com](https://factorio.com/). If the game was bought on Steam, link that account from the [official FAQ](https://factorio.com/support/faq).
3. On the [download page](https://factorio.com/download), choose the **Linux ARM64 graphical full client `.tar.xz`**. If that version is not listed, switch to the experimental channel beside it. For Space Age, download only the Space Age package; you do not need to download the base game as well.
4. Put the original archive somewhere the Android file picker can open. On the launcher home screen, choose **Select game package**. Do not unpack it first.
5. Choose **Launch game**.

Game import opens the archive, extracts files, checks the package structure and ARM64 engine, then switches the active package and removes the previous copy. During extraction, the launcher shows compressed-package read progress, extracted size, file count, and elapsed time. If the document provider does not supply a total size, it shows the bytes read instead. This is not an overall time estimate: verification and cleanup follow extraction. Duration depends on package size, the document provider and device storage performance. Keep the app open.

The home screen offers **Open saves**, **Import save**, and **Open mods**. Select an original `.zip` save without extracting it. Existing saves are preserved and duplicate names receive a numeric suffix. Save and end the game session before editing saves or mods.

Saves created inside the game live in the app's private directory:

```text
files/profiles/default/userdata/saves/
```

Mods are stored in `mods/` under the same `userdata` directory, and game settings in `files/profiles/default/config.ini`. Switching game versions or between base game and Space Age packages continues to use this shared data.

Installing a newer APK with the same package name and signature keeps the imported game, settings and saves. Uninstalling the app or clearing its data deletes those files.

## Requirements

A 64-bit ARM Android device running at least Android 8.0 (API 26), with a Vulkan driver suitable for Mesa Zink, is required. The OS version and CPU architecture are basic conditions; GPU drivers and runtime components must also be compatible.

This project does not yet provide virtual sticks or buttons. Use an Android-compatible gamepad or keyboard and mouse.

Leave room for the original archive, extracted game, temporary import copy, saves and mods. Memory requirements depend on base game / Space Age content, maps, mods, texture quality and loading concurrency. Launch settings expose texture levels, render resolution and a loading thread limit. See [Loading speed and memory (Chinese)](docs/LOADING-MEMORY.md).

## Development

See [Mesa compatibility builds](docs/MESA-COMPATIBILITY.md) for provider source, patches and build inputs.

See [Native library origins and download instructions (Chinese)](docs/NATIVE-LIBRARIES.md) for each packaged `.so`, its role, download location, original archive member and extraction steps. It also covers XKB data and the additional Compose/AndroidX library. See [Android keyboard integration (Chinese)](docs/ANDROID-KEYBOARD.md) for request routing, Unicode commits and helper build instructions.

Build requirements: JDK 17+, SDK platform 36 and Build Tools 36.0.0. AGP 9.0.1 supplies Kotlin 2.2.10; Gradle 9.1.0 and Compose BOM 2026.01.00 are pinned. The wrapper JAR and Gradle distribution have fixed SHA-256 checksums. The app uses minSdk 26 and targetSdk 36.

On Windows, prepare a project-local toolchain if the existing JDK/SDK is too old:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\prepare-windows-toolchain.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1 -PerformancePackage
```

JDK/SDK tools go under `tools/`, and downloads/Gradle state under `.cache/`. No system installation or persistent environment changes. To reuse compatible existing tools, pass `-Jdk` and `-Sdk` to the build script instead.

On Linux x86_64 or macOS with JDK 17+ and an Android SDK:

```bash
JAVA_HOME=/path/to/jdk ANDROID_HOME=/path/to/sdk ./scripts/build.sh --performance-package
```

Zig 0.15.2 for input adapters downloads automatically on Linux x86_64 and macOS. The standard package is `dev.plutocrown.factorio`; the optional performance package is `com.miHoYo.Yuanshen`. Both APKs and `build-result.json` are in `artifacts/`. Both scripts run Gradle unit tests (existing checks plus keyboard request/composition checks) and verify signatures, alignment, package identities, the pinned runtime libraries, XKB, the AndroidX path library and the native components generated from source inside each APK. The compile-only JNI annotation stub is not packaged. Verified third-party runtime libraries are neither rebuilt nor stripped by Gradle. The Vulkan ICD, keyboard request helper, SDL input-method adapter and gamepad adapter are compiled from source during the build using project-local Zig 0.15.2; see the keyboard instructions.

Both entry points restore the pinned runtime automatically. To restore separately (JDK 11+):

```bash
java scripts/RestoreRuntime.java
```

Offline restore: `java scripts/RestoreRuntime.java --archive /path/to/factorio-android-runtime-arm64-20261021-v1.zip`. Use `--check` to verify existing files, or `--force` to explicitly replace changed files. This restores prebuilt dependencies rather than rebuilding all upstream components.

Signing uses the existing project-local key or CI secrets; a key is generated if none is configured. Keep the same signing key for future upgrades. Application tags build and publish APKs to Releases; `runtime-` tags are reserved for pinned dependencies. To publish manually, use Actions → Release → Run workflow and fill `release_tag` with the new application version. Leave it empty to only build Actions artifacts. Existing tags are not moved.

## License

The project-owned launcher, native adapters, tests, and build scripts are [GPL-3.0-or-later](LICENSE), Copyright (C) 2026 Pluto_CRown. Third-party components keep their own licenses. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## GitHub Star History

[![Star History Chart](https://api.star-history.com/svg?repos=PlutoCRown/FactorioAndroid&type=Date)](https://www.star-history.com/#PlutoCRown/FactorioAndroid&Date)

For every project-owned or modified native component, source inputs, build commands and output paths are documented in [SOURCE-BUILD.md](docs/SOURCE-BUILD.md).
