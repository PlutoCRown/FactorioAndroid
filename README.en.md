# Factorio Android Launcher

[![GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)
![Android ARM64](https://img.shields.io/badge/platform-Android%20ARM64-3DDC84.svg)

[简体中文](README.md) | **English**

Run your own official Linux ARM64 Factorio on Android. This is an unofficial launcher and is not affiliated with Wube Software.

## About

### Introduction

The launcher presents the game through Mesa Zink and Vulkan onto an Android Surface, so it runs natively on an Android device. This project does not include or distribute Factorio game files. Download and keep your own legitimate copy from the official site, preferably purchased and downloaded with your own account. The launcher can import, start, and play the game only when you have an official game package.

### Features

| Feature                            | Status |
| ---------------------------------- | ------ |
| Base game runs                     | ✅     |
| Save import                        | ✅     |
| Gamepad, keyboard, and mouse input | ✅     |
| Touchscreen mouse                  | ✅     |

A complete game has been played on a real device. There is no separate screen yet for importing or exporting saves and mods. Keyboard, mouse, and SDL gamepad events are delivered to the game. Touch currently holds the left button and drags with one finger; a fuller touch mouse is still being finished.

## How to play

1. Open [Releases](https://github.com/PlutoCRown/FactorioAndroid/releases), download this app, and install it. The standard package is `FactorioLauncher-0.2.0.apk`.
2. Sign in at [factorio.com](https://factorio.com/). If the game was bought on Steam, link that account from the [official FAQ](https://factorio.com/support/faq).
3. On the [download page](https://factorio.com/download), choose the **Linux ARM64 graphical full client `.tar.xz`**. If that version is not listed, switch to the experimental channel beside it. For Space Age, download only the Space Age package; you do not need to download the base game as well.
4. Put the original archive somewhere the Android file picker can open. On the launcher home screen, choose **Select game package**. Do not unpack it first.
5. Choose **Launch game**.

Saves created inside the game live in the app's private directory:

```text
files/profiles/default-2.1.22/userdata/saves/
```

Installing a newer APK over the old one keeps the imported game, settings, and saves. Uninstalling the app or clearing its data deletes those files.

Import and launch do not use a game-version or engine-hash allowlist. Use a full Linux ARM64 graphical client. Engine digests serve diagnostics and optional adapter selection. The native gamepad adapter activates only for an engine with matching interfaces; other versions still launch with touch or keyboard/mouse input.

## Requirements and tested devices

A 64-bit ARM Android device running at least Android 8.0 (API 26) is required.

- Memory: reserve 4 GB for low quality and 8 GB for high quality. When choosing a device, leave another 4 GB beyond that.
- Storage: reserve 4 GB for the base game and 8 GB for Space Age.

| Device               | Model                                       | Result                                                                                                                          |
| -------------------- | ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| Xiaomi Pad 9 Pro Max | XRing O3, Mali-G2-Ultra-NX MC16, Android 17 | 3408×2272 at full high quality, startup about 15 s, new save render 10 ms and update 0.1 ms; exact figures still to be measured |

## Development

See [Native library origins and download instructions (Chinese)](docs/NATIVE-LIBRARIES.md) for each packaged `.so`, its role, download location, original archive member and extraction steps. It also covers XKB data and the additional Compose/AndroidX library. See [Android keyboard integration (Chinese)](docs/ANDROID-KEYBOARD.md) for request routing, Unicode commits and helper build instructions.

The 2026-10-21 Windows local 0.3.5 standard APK is 31,400,495 bytes (31.40 MB), 5,236 bytes above 0.3.4. All 223 host checks, signature, alignment and payload verification pass. Launch settings offer a separate sprite loading thread limit: 1, 2, 4, 8, 16, 32, or follow the existing game setting. The default is 1 to reduce loading memory peaks; this does not cap gameplay threads. All managed graphics presets use high-quality texture compression.

The launcher UI uses **Kotlin + Jetpack Compose + Material 3** with a Factorio color scheme. The Java import/session services and the native Zink → Android Surface path are retained. Open this folder as a standard Gradle project in Android Studio. The game window remains a separate Activity/SurfaceView.

Build requirements: JDK 17+, SDK platform 36 and Build Tools 36.0.0. AGP 9.0.1 supplies Kotlin 2.2.10; Gradle 9.1.0 and Compose BOM 2026.01.00 are pinned. The wrapper JAR and Gradle distribution have fixed SHA-256 checksums. Android minSdk 26 and targetSdk 33 stay unchanged for this UI migration.

On Windows, prepare a project-local toolchain if the existing JDK/SDK is too old:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\prepare-windows-toolchain.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1 -PerformancePackage
```

JDK/SDK tools go under `tools/`, and downloads/Gradle state under `.cache/`. No system installation or persistent environment changes. To reuse compatible existing tools, pass `-Jdk` and `-Sdk` to the build script instead.

On Linux x86_64 with an existing JDK/SDK:

```bash
JAVA_HOME=/path/to/jdk ANDROID_HOME=/path/to/sdk ./scripts/build.sh --performance-package
```

This project does not install Android tools on the author's Mac; development builds use Windows or GitHub Actions. The standard package is `dev.plutocrown.factorio`; the optional performance package is `com.miHoYo.Yuanshen`. Both APKs and `build-result.json` are in `artifacts/`. Both scripts run Gradle unit tests (existing checks plus keyboard request/composition checks) and verify signatures, alignment, package identities, all 52 game runtime library hashes, XKB, the additional AndroidX path library and the keyboard request helper inside each APK. The compile-only JNI annotation stub is not packaged. Verified third-party runtime libraries are neither rebuilt nor stripped by Gradle. The small keyboard helper is compiled from source during the build using project-local Zig 0.15.2; see the keyboard instructions.

Both entry points restore the pinned runtime automatically. To restore separately (JDK 11+):

```bash
java scripts/RestoreRuntime.java
```

For a private repository, set `GITHUB_TOKEN` or `GH_TOKEN` with Contents: read access. No token belongs in source or logs. Offline: `java scripts/RestoreRuntime.java --archive /path/to/factorio-android-runtime-arm64-20261021-v1.zip`. Use `--check` to verify existing files, `--force` to explicitly replace changed files. Binaries stay outside Git; the restorer checks the archive and all 53 files. This restores prebuilt dependencies rather than rebuilding all upstream components.

Signing uses the existing project-local key or CI secrets; a key is generated if none is configured. Keep the same signing key for future upgrades. Application tags build and publish APKs to Releases; `runtime-` tags are reserved for pinned dependencies. To publish manually, use Actions → Release → Run workflow and fill `release_tag` with the new application version. Leave it empty to only build Actions artifacts. Existing tags are not moved.

## License

The project-owned launcher, native adapters, tests, and build scripts are [GPL-3.0-or-later](LICENSE), Copyright (C) 2026 Pluto_CRown. Third-party components keep their own licenses. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

This grant does not include the Factorio name, trademarks, engine, or game data, and it does not include `factorio_icon.png` or `factory_background.jpg`.

## GitHub Star History

[![Star History Chart](https://api.star-history.com/svg?repos=PlutoCRown/FactorioAndroid&type=Date)](https://www.star-history.com/#PlutoCRown/FactorioAndroid&Date)
