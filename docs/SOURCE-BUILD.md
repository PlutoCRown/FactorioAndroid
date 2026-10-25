# 从源码还原启动器自有组件

第三方原版运行库的下载来源见 [NATIVE-LIBRARIES.md](NATIVE-LIBRARIES.md)。本文覆盖启动器自行编译、修改或适配的所有原生组件，以及它们如何进入 APK。游戏本体不属于构建输入，由用户导入。

## 构建前提

在工程根目录执行命令。APK 构建使用 JDK 17+、Android SDK platform 36、Build Tools 36.0.0 和工程 Gradle Wrapper；具体准备入口为 `scripts/prepare-windows-toolchain.ps1`。签名密钥由构建入口生成或从环境提供，不能从源码恢复另一台电脑的私钥；保留相同应用数据升级时必须继续使用原密钥。

原生编译器为工程内的 Zig 0.15.2，由 `scripts/BuildImeNative.java` 按主机准备并校验，来源在 `scripts/ime-compiler.properties`。它同时提供 GNU/Linux ARM64 和 Android ARM64 代码生成。Android 接收器不再需要测试设备上的 libc/libdl 副本，也不依赖历史工作目录。

第三方 GNU 链接库仍需恢复，首次执行：

```powershell
java scripts/RestoreRuntime.java
```

恢复清单不再要求预先存在自有 TLS、Surface 接收器、Vulkan ICD、手柄和输入法 `.so`。归档中旧自有文件不会覆盖源码生成的产物。标准构建和源码构建都不需要 ADB 地址、设备型号、游戏引擎哈希或某个历史档案。

## 自有原生组件清单

下表路径相对于工程根目录。`app/build/generated/ime/jni/arm64-v8a/` 是自有组件的统一输出目录；目录名称保留现有 Gradle 接口，不表示其中只有输入法。

| 产物 | 参与编译的实现及头文件 | 编译入口与目标 |
| --- | --- | --- |
| `librea_direct_icd.so` | `native-input/direct-wsi/upstream/icd.c`、`instance.c`、`device.c`、`swapchain.c`、`wsi.c`；`native-input/direct-wsi/native-window-surface.c`；同目录 `upstream/*.h`、`physical_commands.inc` 与 `include/` 声明 | `BuildImeNative.directIcd()`，`aarch64-linux-gnu.2.38`，共享库，链接已恢复的 hybris/hardware/gralloc/ui/XCB/Xlib-XCB 接口 |
| `librea_direct_present.so` | `native-input/direct-present-server.c`、`native-input/graphics-capabilities.c`；`native-input/direct-wsi/include/vulkan/`；`native-input/direct-present-include/EGL/{egl.h,eglext.h,eglplatform.h}`、`KHR/khrplatform.h`；Android 公共头文件 | `scripts/BuildAndroidReceiver.java`，`aarch64-linux-android.26`，共享库 |
| `librea_gnu_tls.so` | `native-input/gnu-tls/bionic_tls.c`、`bionic_tls.h`、`logging.h`、`config.h` | `scripts/BuildImeNative.java`，GNU ARM64 共享库，initial-exec TLS 与 `nodelete` |
| `librea_gamepad_bridge.so` | `native-input/gamepad-bridge.c`、`native-input/sdl-symbols.h` | `scripts/BuildImeNative.java`，GNU ARM64 共享库，链接 dl/pthread |
| `librea_ime_opener.so` | `native-input/ime/keyboard-opener.c` | `scripts/BuildImeNative.java`，`aarch64-linux-musl` 静态可执行程序，以 `.so` 名打包 |
| `librea_ime_hint.so` | `native-input/ime/sdl-hint-guard.c`、`native-input/sdl-symbols.h` | `scripts/BuildImeNative.java`，GNU ARM64 共享库，链接 dl/pthread |
| `librea_compat_direct_icd.so` | 与 `librea_direct_icd.so` 相同的当前源码及编译产物 | `scripts/BuildMesaNative.java` 将本轮源码产物以兼容运行库名称暂存，不复用缓存中的旧 ICD |
| `librea_compat_direct_present.so` | 与 `librea_direct_present.so` 相同的当前源码及编译产物 | `scripts/BuildMesaNative.java` 将本轮源码产物以兼容运行库名称暂存，不复用缓存中的旧接收器 |
| `librea_legacy_mesa_{gallium,egl,glx,gbm,glapi}.so` | `native-input/mesa-legacy/inputs.json` 的 Mesa 23.1.9，加该目录四份补丁 | `scripts/build-legacy-mesa.py` 生成 GNU provider；`scripts/BuildLegacyMesaNative.java` 组装入 APK，见 [完整命令](../native-input/mesa-legacy/README.md) |
| `librea_compat_mesa_{gallium,egl,glx,gbm,gbm_dri,dril}.so` | `native-input/mesa-compat/inputs.json` 指定的完整 Mesa 源码，加 `qualcomm-static-color-write.patch` | `scripts/build-mesa-runtime.py --providers-only`，GNU ARM64；下节列出完整命令和输出 |

普通源码构建命令：

```powershell
java scripts/BuildImeNative.java
```

这个命令会从实际源码生成前六个文件，并写出 `app/build/generated/ime/assets/ime-native.properties`。构建记录用于核对 APK 是否确实包含这次编译的文件；应用运行时不使用源码哈希来限制游戏版本。

只重建 Android 接收器，要求已经准备 Zig：

```powershell
java scripts/BuildAndroidReceiver.java
# 也可传入输出文件路径。
java scripts/BuildAndroidReceiver.java C:\build\librea_direct_present.so
```

只准备 GNU 编译器、GNU 组件和链接依赖、不编译 Android 组件时：

```bash
java scripts/BuildImeNative.java --gnu-only
```

该模式供只编译 Mesa 的 macOS/Linux 主机使用，无需 Android SDK、NDK 或 ADB。不能用它的半成品直接当作完整 APK 构建结果。

## Android 公共头文件与链接输入

`BuildAndroidReceiver.java` 自动获取 [Termux ndk-sysroot 30 头文件包](https://packages.termux.dev/apt/termux-main/pool/main/n/ndk-sysroot/ndk-sysroot_30_aarch64.deb)，验证后解包到 `.cache/android-native/headers/`。不安装包、不运行包内脚本。EGL/KHR 头文件已随源码保留，其许可和来源见 `native-input/direct-present-include/SOURCES.md`。

`native-input/android-link-stubs.c` 只声明接收器实际使用的公共 libc/libdl 符号；构建器把它编译为 `.cache/android-native/libc.so`、`libdl.so` 这两个链接专用库。它们的函数体不能使用，绝不进入 APK；运行时 SONAME 由 Android 系统的真实 libc/libdl 解析。链接采用 `--no-undefined`，新增依赖必须明确补充并核对最低 API。这样源码重建不需要提取任何设备系统库，也不需要完整 NDK 安装。

这些公共接口均在 API 26 或更早提供；JNI、ANativeWindow、AHardwareBuffer、EGL/GLES 的调用按源码中的动态解析路径使用。新功能不能通过在链接声明中写一个名字就假定运行系统支持，仍需运行时能力和返回值检查。

## TLS 适配层

`native-input/gnu-tls/` 保存了本项目实际使用的适配源码，保留 libhybris 的 Apache-2.0 版权。上游基础来自 [taowen/libhybris 的 TLS 实现](https://github.com/taowen/libhybris/blob/2375acd0b5d278a0a2f745c645680545e8f80539/hybris/common/bionic_tls.c)。这里的 16 KiB 静态 TLS、边界检查及线程初始化以工程内文件为准，不能只下载上游同名文件代替。

独立命令与构建入口采用相同目标和参数：

```bash
tools/zig-aarch64-macos-0.15.2/zig cc \
  -target aarch64-linux-gnu.2.38 -std=c11 -O2 -D_GNU_SOURCE \
  -Wall -Wextra -Werror -fPIC -shared -Wl,-z,nodelete \
  -Wl,-z,max-page-size=16384 native-input/gnu-tls/bionic_tls.c \
  -pthread -o librea_gnu_tls.so
```

Windows 使用对应的 `tools\zig-x86_64-windows-0.15.2\zig.exe`；完整 APK 构建无需手动执行此命令。

## 修改过的 Mesa：从源码生成六个 provider

Mesa 的原始源码归档、源码提交、依赖开发头文件包及工具版本全部在 `native-input/mesa-compat/inputs.json`；本项目修改在 `native-input/mesa-compat/qualcomm-static-color-write.patch`。构建脚本会验证源码并应用该补丁，不能以原版 Mesa 的同名库代替。

使用 macOS 或 Linux，准备 Python 3.10+（含 venv/pip）、主机 C/C++ 编译器、make、ar、patch。工具和开发头文件只放在工程 `.cache/`；下载的 Debian 包只提供头文件，GNU 链接库来自已恢复的运行库。macOS 必须为主机编译器选择与其匹配的 macOS SDK；必要时为这一条命令设置 `SDKROOT`，不要混用另一版 Command Line Tools 的 SDK。

```bash
# 现有 JDK 即可；以下两步不需要 Android SDK/NDK。
java scripts/RestoreRuntime.java
java scripts/BuildImeNative.java --gnu-only
python3 scripts/build-mesa-runtime.py --providers-only
```

脚本使用工程内 Meson/Ninja/Mako 等工具，Zig 交叉编译目标为 `aarch64-linux-gnu.2.38`，启用 X11、Zink、GLX、EGL、GBM、GLVND，关闭其他 Vulkan/Gallium 驱动、LLVM、VA、测试及附加工具。准确完整参数以脚本中 `opts` 为准。

输出 `.cache/mesa-providers.zip`：六个 Mesa provider 和 `mesa-native.properties`。其中记录源码版本、补丁/构建输入与各产物信息。这个 ZIP 不含 Android 接收器和自有 ICD；修改自有适配代码时不必重编整套 Mesa。

把这份**自己从源码构建的产物**复制到 Windows 工程 `.cache/mesa-providers.zip`，或使用环境参数：

```powershell
$env:FACTORIO_MESA_ARCHIVE='C:\build\mesa-providers.zip'
java scripts/BuildImeNative.java
java scripts/BuildMesaNative.java
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1
```

`BuildMesaNative` 核对六个 provider，再使用本轮源码生成的 ICD 和接收器组成完整的八文件兼容运行库，输出到 `app/build/generated/mesa/`。不要求从别人电脑或发布页取得既有兼容 ZIP。`BuildMesaNative` 同时通过 `BuildLegacyMesaNative.java` 组装传统路径的五个 provider，Windows 需要另外准备源码构建的 `.cache/mesa-legacy-providers.zip` 或 `FACTORIO_LEGACY_MESA_ARCHIVE`；Linux/macOS 缺缓存时自动源码构建。完整来源、补丁和命令见 [传统路径说明](../native-input/mesa-legacy/README.md)。Windows 原生编译 Mesa 本体的路径尚未接入；可在 macOS/Linux 生成 GNU provider，Windows 完成其余源码与 APK 构建。

## APK 暂存及检查

Gradle 的 `buildImeNative`、`buildMesaNative`、`stageGameRuntime` 是实际入口。`stageGameRuntime` 从恢复目录排除四个可能残留的自有文件名，避免旧 `jniLibs` 副本覆盖本轮产物。`scripts/BuildLauncher.java` 运行主机检查，再逐项核对基线、八个兼容组件、五个传统 Mesa provider、六个本轮自有组件、AndroidX 路径库和 XKB。

完整构建仍是：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1
```

标准 APK、版本、大小和检查结果在 `artifacts/`。没有签名密钥时可生成新的开发密钥构建，但不能用它保留数据覆盖由另一把密钥签名的已有应用。构建过程不需要游戏包、ADB 配对证书或测试设备。

## 其他由工程生成的文件

Java/Kotlin/Compose 界面、Android 资源及 JNI 声明由标准 Gradle 构建编译，不需要补回任何反编译文件。源码目录分别为 `app/src/main/java/`、`app/src/main/res/` 和 `compile-stubs/`；stub 只用于编译，不进入 APK。

Lucide 原始 SVG 和许可保存在 `app/src/main/assets/lucide/`。如需重新生成已提交的 Android VectorDrawable：

```bash
java scripts/ConvertLucideIcons.java
```

XKB 是第三方键盘布局数据，本项目只做归档和三个规则别名的实体化。可从 `NATIVE-LIBRARIES.md` 链接下载原始 `xkeyboard-config-glibc_2.43_all.deb`，再使用工程内脚本还原 ZIP：

```bash
python3 scripts/build-xkb-data.py /path/xkeyboard-config-glibc_2.43_all.deb --update-manifests
```

脚本读取 DEB 内的数据，不安装包、不执行包内脚本；生成 `app/src/main/assets/window-xkb.zip`，并更新 `window-runtime.json` 和恢复清单中的数据项。原有第三方 `.so` 的版本和清单不变。ZIP 压缩字节可随压缩库版本不同，校验应同时核对解压后的文件集合和内容。

## 会话退出监督的独立设备回归

`tests/device/EngineExitProbe.java` 通过反射调用 APK 中真实的进程监督代码，仅运行自己的短命 shell 子进程，不加载游戏。覆盖正常退出、快速致命异常，以及图形初始化后发生致命异常却不结束的进程。另行覆盖用户退出完成后才发生的清理故障：保留信号诊断，但标记正常退出流程已完成。它不需要独立安装测试 APK。

完成普通 APK 构建后，在 Windows 使用现有 JDK/SDK 编译：

```powershell
$classes='.cache\engine-exit-probe\classes'
New-Item -ItemType Directory -Force $classes | Out-Null
& "$env:JAVA_HOME\bin\javac.exe" --release 8 -d $classes tests/device/EngineExitProbe.java
$inputs=@(Get-ChildItem $classes -Filter '*.class' -Recurse | ForEach-Object FullName)
& "$env:JAVA_HOME\bin\java.exe" -cp "$env:ANDROID_HOME\build-tools\36.0.0\lib\d8.jar" `
  com.android.tools.r8.D8 --min-api 26 --lib "$env:ANDROID_HOME\platforms\android-36\android.jar" `
  --output artifacts/EngineExitProbe.jar @inputs
```

将 JAR 复制到 debuggable 启动器的私有缓存并设为只读。通过 `run-as` 在应用 UID 下，以当前 APK 和 JAR 组成 `CLASSPATH`，运行 `/system/bin/app_process / EngineExitProbe CACHE_DIRECTORY`。APK 路径用 `pm path` 获取，缓存目录按实际 applicationId 取得；连接地址通过 ADB 的参数传入。成功输出 `ENGINE_EXIT_PROBE ... passed=true`。实际游戏退出仍需单独验证，发现原生崩溃时应保留原始信号和终止记录，不将监督器结束子进程产生的退出码当作原始原因。
