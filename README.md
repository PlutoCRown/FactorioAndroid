# Factorio Android Launcher

[![GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)
![Android ARM64](https://img.shields.io/badge/platform-Android%20ARM64-3DDC84.svg)

**简体中文** | [English](README.en.md)

在 Android 上运行你自己的官方 Linux ARM64 Factorio。这是非官方启动器，与 Wube Software 无关。

## 项目说明

### 简介

本启动器通过 GNU/Linux 运行环境、Mesa Zink 和设备的 Vulkan 驱动，将官方 Linux ARM64 图形客户端的画面呈现到 Android Surface。本项目不包含、也不分发 Factorio 的原版游戏文件。你需要在官网下载并持有游戏资源（建议通过官网账号购买和下载）。将游戏安装包导入后，即可通过启动器进行启动和游玩。

### 实现功能

- 导入用户自备的官方 Linux ARM64 图形完整客户端。
- 导入 ZIP 存档，并通过系统文件管理器访问当前游戏档案的存档和模组目录。
- 转发键盘、鼠标与触屏模拟鼠标输入；通过 SDL3 接口接入手柄和 Android 输入法。
- 所有游戏版本及原版／Space Age 共用配置、存档与模组。

这些功能依赖游戏接口和设备运行环境。启动器会报告缺失的原生接口或运行错误；支持导入某个安装包不代表该版本能在所有设备上正常运行。

## 食用指南

1. 打开 [Releases](https://github.com/PlutoCRown/FactorioAndroid/releases)，下载并安装本应用。选择所需版本的标准 APK。
2. 登录 [Factorio 官网](https://factorio.com/)。游戏买在 Steam 时，按[官方 FAQ](https://factorio.com/support/faq) 把 Steam 关联到官网账号。
3. 在[官方下载页](https://factorio.com/download)选择 **Linux ARM64 图形完整客户端 `.tar.xz`**。如果没有这个版本可以切换到旁边的实验版。太空时代只需下载太空时代包即可，无需再下载原版。
4. 把原始压缩包放到手机能用文件选择器打开的目录。在启动器主页点「选择游戏安装包」，不需要提前解压。
5. 点「启动游戏」。

导入本体会依次打开安装包、解压文件、检查安装包结构与 ARM64 引擎、切换本体并清理旧副本。解压阶段显示安装包读取百分比、已展开大小、文件数和耗时；文件提供者未返回总大小时显示已读取的数据量。读取进度不代表整体耗时，之后仍有核验与清理。耗时取决于安装包大小、文件提供者和设备存储性能，请保持应用打开。

主页提供「打开存档文件夹」「导入存档」和「打开模组文件夹」。导入存档请选择原始 `.zip`，无需解压；同名文件自动另存，不覆盖原存档。更改存档或模组前，请保存并结束游戏会话。

游戏内新建的存档在应用私有目录：

```text
files/profiles/default/userdata/saves/
```

模组保存在同一 `userdata` 下的 `mods/`，游戏设置保存在 `files/profiles/default/config.ini`。更换游戏版本或原版／Space Age 安装包后，继续使用这份数据。

用同包名、同签名的新 APK 覆盖安装会保留导入的游戏、设置和存档。卸载或清除数据会删掉这些文件。


## 运行要求

需要 64 位 ARM Android，系统版本至少为 8.0（API 26），并有能满足 Mesa Zink 需求的 Vulkan 驱动。系统版本和 CPU 架构只是基本条件，不能替代 GPU 驱动与运行库兼容性检查。

本项目暂未提供虚拟摇杆/按键，请自备可连接安卓设备的手柄或者键盘鼠标使用。

为原始安装包、展开后的游戏文件、导入时的临时副本以及存档和模组保留足够存储空间。内存需求随原版／Space Age、地图、模组、贴图质量和加载并发变化；可在启动设置中调整贴图级别、渲染分辨率和加载线程上限，详见[加载速度与内存](docs/LOADING-MEMORY.md)。

## 开发指南

图形兼容运行库的源码、补丁、构建和复用步骤见 [Mesa 兼容构建](docs/MESA-COMPATIBILITY.md)。

`jniLibs` 中各个 `.so` 的来源、用途、下载位置、原包成员路径和获取步骤，见 [原生库来源与获取说明](docs/NATIVE-LIBRARIES.md)。文档也说明XKB数据、Compose新增AndroidX库及软键盘请求程序。安卓输入法的触发、文字回传和原生程序编译步骤见 [安卓软键盘接入](docs/ANDROID-KEYBOARD.md)。

构建需要 JDK 17+、SDK platform 36、Build Tools 36.0.0。固定 AGP 9.0.1（内置 Kotlin 2.2.10）、Gradle 9.1.0、Compose BOM 2026.01.00；Wrapper JAR 与 Gradle 发行包均有固定 SHA-256。应用使用 minSdk 26、targetSdk 36。

Windows 现有工具过旧时，可只在工程内准备工具链：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\prepare-windows-toolchain.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1 -PerformancePackage
```

JDK/SDK 放在 `tools/`，下载与 Gradle 缓存放在 `.cache/`；不安装到系统、不永久修改环境。复用现有兼容工具时，直接给构建脚本传 `-Jdk`、`-Sdk`。

Linux x86_64 或 macOS 已有 JDK 17+ 和 Android SDK 时：

```bash
JAVA_HOME=/path/to/jdk ANDROID_HOME=/path/to/sdk ./scripts/build.sh --performance-package
```

输入适配程序用的 Zig 0.15.2 在 Linux x86_64 与 macOS 上会按主机自动下载。普通包 ID 为 `dev.plutocrown.factorio`；可选性能包 ID 为 `com.miHoYo.Yuanshen`。两个 APK 与 `build-result.json` 输出到 `artifacts/`。构建入口运行 Gradle 单元测试（原有检查及键盘请求/组合文字检查），再检查签名、对齐、包名，以及 APK 内固定运行库、XKB、AndroidX 路径库和从源码生成的原生组件。JNI注解stub仅编译期使用，不进入APK；Gradle不重建或剥离已校验的第三方运行库；Vulkan ICD、键盘请求程序、SDL 输入法适配器和手柄适配器随构建从源码编译，需要工程内 Zig 0.15.2，准备方式见键盘说明。

两个入口均自动恢复固定运行库；也可单独执行（此恢复命令只需JDK11+）：

```bash
java scripts/RestoreRuntime.java
```

离线恢复：`java scripts/RestoreRuntime.java --archive /path/to/factorio-android-runtime-arm64-20261021-v1.zip`；`--check` 仅验证；`--force` 明确替换已改动文件。这是恢复预编译依赖，不等于从源码重建所有第三方组件。

签名复用工程内密钥或CI secrets；未配置则生成项目密钥，后续升级需保留同一签名。推送应用版本标签自动构建并上传Releases，`runtime-`标签保留给依赖附件。手动发布：Actions → Release → Run workflow，`release_tag`填新应用版本；留空仅构建并保存Actions附件。已有标签不移动。

Release 正文读取 `docs/releases/<factorioVersionName>.md`。更新版本时，将对应说明文件与源码一起提交；工作流按 `gradle.properties` 中的实际 APK 版本选择说明，不读取 tag 注释。完整流程见[版本发布说明](docs/releases/README.md)。云端新增图形兼容库的生成与打包路径见 [Mesa 兼容构建](docs/MESA-COMPATIBILITY.md#github-actions-如何取得新增库)。

## 许可证

自有启动器、原生适配、测试和构建脚本使用 [GPL-3.0-or-later](LICENSE)，版权归 Pluto_CRown（2026）。第三方组件保持各自的原文许可证，见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

## GitHub Star History

[![Star History Chart](https://api.star-history.com/svg?repos=PlutoCRown/FactorioAndroid&type=Date)](https://www.star-history.com/#PlutoCRown/FactorioAndroid&Date)

自有或修改过的原生组件如何从源码生成、构建命令和产物位置，见 [SOURCE-BUILD.md](docs/SOURCE-BUILD.md)。
