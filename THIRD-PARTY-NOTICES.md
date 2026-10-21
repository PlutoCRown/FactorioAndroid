# Third-party components

For binary download locations, original archive members, roles and extraction steps, see [Native library origins and download instructions (Chinese)](docs/NATIVE-LIBRARIES.md).

This private source repository is an Android compatibility launcher for a user-supplied game. It contains no Factorio engine, installation archive, save, account credential, proprietary GPU driver, or precompiled runtime library. Project-owned launcher code, native adapters, tests, and build scripts are GPL-3.0-or-later, Copyright (C) 2026 Pluto_CRown; the text is `LICENSE`. Game names and local UI artwork remain their respective owners' property. No license is granted here for redistribution of Wube artwork, including `factorio_icon.png` and `factory_background.jpg`.

The local build restores the following separately pinned runtime components. Their original licenses apply; this project does not relicense third-party code. Full retained copyright/license texts are in `app/src/main/assets/*COPYRIGHT.txt`. Some collected notice bundles also cover historical components that are no longer packaged. Their presence does not mean those renderers remain in the application.

## Direct graphics and platform bridge

- GNU Mesa 26.3.0-devel from the pinned taowen/mesa component; Vulkan Loader 1.3.301; libhybris arlinux. Exact payload hashes are in `app/src/main/assets/direct-zink.json`. The direct route does not use VirGL, ANGLE or a software game renderer. Shared GNU dependencies, including libglvnd and discovery libraries, are listed in `direct-dependencies.json`.
- The public Vulkan ICD core comes from [taowen/libhybris](https://github.com/taowen/libhybris), commit `2375acd0b5d278a0a2f745c645680545e8f80539`. Apache-2.0 notices remain in the selected sources. `native-input/direct-wsi/sources.json` identifies inputs and modifications. Component metadata does not prove that a prebuilt library corresponds to a separately inspected source revision.
- Project-owned `native-window-surface.c` replaces the upstream TAWC-DRI transport with a private AHardwareBuffer socket. `direct-present-server.c` samples that GPU image into the Android Surface. Release fences and consumer completion acknowledgement remain.
- Selected [Khronos Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers) at commit `952f776f6573aafbb62ea717d871cd1d6816c387` and Android Open Source Project ABI headers retain their source notices. These are build headers, not proprietary driver binaries.

## GNU runtime, windows and Java dependency

- GNU C Library 2.44, Android-adapted [Termux glibc-packages](https://github.com/termux/glibc-packages/tree/main/gpkg/glibc), four unchanged libraries renamed to private packaged filenames. Hashes: `runtime-glibc.json`. Retained notices: `GLIBC-COPYRIGHT.txt`. These GNU libraries are used in a separate engine child, never loaded into ART with `System.loadLibrary`.
- [Termux:X11](https://github.com/termux/termux-x11), commit `fa3a8b430e2896a19f44c99a9cb056254615ae06`: pinned upstream ARM64 `libXlorie.so`, with the minimal Java JNI/window/input bridge adapted in this project. Upstream project GPL-3.0-or-later; retained texts: `WINDOW-COPYRIGHT.txt`. No Termux application, shared UID or Termux app IPC is required. X11 provides window/input protocols; it no longer carries the game's full-frame pixels.
- xkeyboard-config 2.43 provides XKB data; package permissions remain in `WINDOW-COPYRIGHT.txt`. GNU X11 client libraries and related dependencies are pinned by `window-clients.json` and `direct-dependencies.json`; retained notices include `WINDOW-CLIENT-COPYRIGHT.txt`, `GL-COPYRIGHT.txt` and `EGL-COPYRIGHT.txt`.
- [XZ for Java 1.12](https://tukaani.org/xz/java.html), Lasse Collin and contributors, BSD Zero Clause (0BSD). Sources: [xz-java](https://github.com/tukaani-project/xz-java), Maven artifact `org.tukaani:xz:1.12`. Resolved as an Android dependency by Gradle.

## Local interface assets

`factorio_icon.png` was extracted from the user's own game. `factory_background.jpg` is the user-requested Factorio website background. Both remain Wube artwork, kept here for the owner's private project; public redistribution permission has not been established. `ic_expand_more.xml` is a project-owned vector chevron. Button colors and bevel/shadow parameters follow the user-provided Factorio website `normal.css` selectors `.button` and `.button-green`, including hover/focus, pressed and disabled states. `FactoryWebButton.kt` implements those appearances with a cached Android Canvas drawing and Compose interactions; touch targets retain an Android-appropriate minimum height. This does not grant any rights to Wube website assets.

The sidebar uses official [Lucide](https://lucide.dev/) `house`, `sliders-horizontal` and `tablet` icons. Original SVGs, pinned download URLs and the complete ISC / inherited Feather MIT license are retained in `app/src/main/assets/lucide/`. `scripts/ConvertLucideIcons.java` converts those SVG paths into Android vector drawables without changing their geometry or stroke style; no icon library or online request is needed at runtime. Reproduce with `java scripts/ConvertLucideIcons.java` from the project directory. The original icon licenses apply to the generated vectors.

## Kotlin / Jetpack Compose / Gradle

AndroidX Activity, Compose UI/Foundation/Material 3 and their AndroidX dependencies are from Google/AOSP under Apache-2.0. Kotlin standard library is from JetBrains under Apache-2.0. The Gradle wrapper is the verified official Gradle 9.1.0 wrapper, Apache-2.0. Apache-2.0 text is retained in `app/src/main/assets/APACHE-2.0.txt`. These components do not replace or modify the game's renderer. Project-owned Compose theme code remains GPL-3.0-or-later; the Lucide navigation vectors retain the licenses above.

Compose also bundles `androidx.graphics:graphics-path:1.0.1` (Apache-2.0). Its ARM64 `libandroidx.graphics.path.so` is pinned separately in `scripts/compose-native-files.tsv`; it is not part of the 52-library game runtime.

## Android keyboard request helper

Project-owned `native-input/ime/keyboard-opener.c` is GPL-3.0-or-later. `librea_ime_opener.so` is a static ARM64 executable built by Zig 0.15.2 with its bundled musl C library. musl copyright and license texts are retained in `app/src/main/assets/IME-MUSL-COPYRIGHT.txt`. This helper receives SDL keyboard URLs; it does not load or modify the game. Compiler sources and reproduction steps are in [Android keyboard integration (Chinese)](docs/ANDROID-KEYBOARD.md).
