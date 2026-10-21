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

## Requirements and tested devices

A 64-bit ARM Android device running at least Android 8.0 (API 26) is required.

- Memory: reserve 4 GB for low quality and 8 GB for high quality. When choosing a device, leave another 4 GB beyond that.
- Storage: reserve 4 GB for the base game and 8 GB for Space Age.

| Device               | Model                                       | Result                                                                                                                          |
| -------------------- | ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| Xiaomi Pad 9 Pro Max | XRing O3, Mali-G2-Ultra-NX MC16, Android 17 | 3408×2272 at full high quality, startup about 15 s, new save render 10 ms and update 0.1 ms; exact figures still to be measured |

## Development

macOS and Linux use `scripts/build.sh`. Windows uses `scripts/build-windows.ps1`. The build does not use Gradle.

```bash
./scripts/build.sh
./scripts/build.sh --performance-package
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1 `
  -Sdk 'D:\Android\Sdk' -Jdk 'D:\Java\jdk-15.0.2'
```

The normal application id is `dev.plutocrown.factorio`, and the APK is written to `artifacts/`. `--performance-package` links a second APK with the application id `com.miHoYo.Yuanshen` so some phones apply that game's performance profile. The visible name remains Factorio.

Restore the pinned runtime from the project root using the JDK already required by the build (JDK 11+):

```bash
java scripts/RestoreRuntime.java
```

On Windows, an explicit existing JDK path also works:

```powershell
& 'D:\Java\jdk-15.0.2\bin\java.exe' .\scripts\RestoreRuntime.java
```

This restores 52 ARM64 libraries and `window-xkb.zip` from a versioned Release asset, checking both the archive and every file against pinned SHA-256 hashes. Binaries remain outside Git. For a private repository, set `GITHUB_TOKEN` or `GH_TOKEN` with Contents: read access; never commit or share a token. A public repository needs no token. Already valid files skip the download. Nothing downloaded is executed and no system tools are installed.

For offline use, download the asset in your browser and run `java scripts/RestoreRuntime.java --archive /path/to/factorio-android-runtime-arm64-20261021-v1.zip`. Use `--check` for verification only. Changed files are preserved unless you explicitly pass `--force`. This recovers pinned prebuilt dependencies; it does not rebuild every upstream component from source.

Both build entry points invoke the restorer automatically and fail if runtime dependencies cannot be restored. The cache is project-local under `.cache/runtime/`. Android SDK and JDK must still be available. Signing keys are local or provided via CI secrets; otherwise a project-local key is generated. Application tags run `.github/workflows/release.yml`; `runtime-` tags host dependency assets and skip application publishing. To publish manually, open Actions → Release → Run workflow, select the source branch, and enter an application tag such as `v0.2.0` in `release_tag`. Both APKs and the build report are uploaded directly to that Release after a successful build. Leave the field empty for build-only Actions artifacts. Existing tags are not moved; a new tag targets the build commit.

## License

The project-owned launcher, native adapters, tests, and build scripts are [GPL-3.0-or-later](LICENSE), Copyright (C) 2026 Pluto_CRown. Third-party components keep their own licenses. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

This grant does not include the Factorio name, trademarks, engine, or game data, and it does not include `factorio_icon.png` or `factory_background.jpg`.

## GitHub Star History

[![Star History Chart](https://api.star-history.com/svg?repos=PlutoCRown/FactorioAndroid&type=Date)](https://www.star-history.com/#PlutoCRown/FactorioAndroid&Date)
