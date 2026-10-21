# Factorio Android Launcher

[![GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)
![Android ARM64](https://img.shields.io/badge/platform-Android%20ARM64-3DDC84.svg)

**简体中文** | [English](README.en.md)

在 Android 上运行你自己的官方 Linux ARM64 Factorio。这是非官方启动器，与 Wube Software 无关。

## 项目说明

### 简介

本启动器通过 Mesa Zink 和 Vulkan 技术将游戏画面传递给 Android Surface，实现了在安卓设备上的高效本地运行。本项目不包含、也不分发 Factorio 的原版游戏文件。你需要在官网下载并持有游戏资源（建议通过官网账号购买和下载）。将游戏安装包导入后，即可通过启动器进行启动和游玩。

### 实现功能

| 功能                 | 状态 |
| -------------------- | ---- |
| 原版正常运行         | ✅   |
| 存档导入             | ✅   |
| 手柄、键盘与鼠标输入 | ✅   |
| 触屏模拟鼠标         | ✅   |

已在真实设备上进入完整游戏。独立的存档和模组导入导出还没有界面。键盘、鼠标和 SDL 手柄会送进游戏。触屏目前是单指按住左键拖拽，完整的触屏鼠标仍在完善。

## 食用指南

1. 打开 [Releases](https://github.com/PlutoCRown/FactorioAndroid/releases)，下载并安装本应用。标准包文件名是 `FactorioLauncher-0.2.0.apk`。
2. 登录 [Factorio 官网](https://factorio.com/)。游戏买在 Steam 时，按[官方 FAQ](https://factorio.com/support/faq) 把 Steam 关联到官网账号。
3. 在[官方下载页](https://factorio.com/download)选择 **Linux ARM64 图形完整客户端 `.tar.xz`**。如果没有这个版本可以切换到旁边的实验版。太空时代只需下载太空时代包即可，无需再下载原版。
4. 把原始压缩包放到手机能用文件选择器打开的目录。在启动器主页点「选择游戏安装包」，不需要提前解压。
5. 点「启动游戏」。

游戏内新建的存档在应用私有目录：

```text
files/profiles/default-2.1.22/userdata/saves/
```

用新的 APK 覆盖安装会保留导入的游戏、设置和存档。卸载或清除数据会删掉这些文件。

## 运行要求和已测试设备

需要 64 位 ARM Android，系统版本至少为 8.0（API 26）。

- 内存：低画质建议预留 4 GB，高画质建议预留 8 GB。实际买设备时，建议在这之上再留 4 GB。
- 存储：原版建议预留 4 GB，太空时代建议预留 8 GB。

| 设备               | 型号                                        | 效果                                                                      |
| ------------------ | ------------------------------------------- | ------------------------------------------------------------------------- |
| 小米平板 9 Pro Max | XRing O3，Mali-G2-Ultra-NX MC16，Android 17 | 3408×2272 全高画质，启动约 15s，新存档 渲染10ms 计算0.1ms，具体数值待测量 |

## 开发指南

`jniLibs` 中各个 `.so` 的来源、用途、下载位置、原包成员路径和获取步骤，见 [原生库来源与获取说明](docs/NATIVE-LIBRARIES.md)。文档也说明XKB数据、Compose新增AndroidX库及软键盘请求程序。安卓输入法的触发、文字回传和原生程序编译步骤见 [安卓软键盘接入](docs/ANDROID-KEYBOARD.md)。

2026-10-21 Windows 本地键盘接入版标准 APK 为31,386,537字节（31.39 MB），比此前 Compose 标准版增加13,665字节。198项主机检查、签名、对齐和载荷校验通过；平板已保留数据升级，原生请求程序通过设备执行与通信测试。游戏输入框自动触发和中文回传仍待真机验收。

启动器 UI 使用 **Kotlin + Jetpack Compose + Material 3**，保留 Factorio 配色。Java 导入/会话服务与原生 Zink → Android Surface 路径继续沿用，游戏窗口仍是独立 Activity/SurfaceView。此目录可作为标准 Gradle 工程在 Android Studio 中打开。

构建需要 JDK 17+、SDK platform 36、Build Tools 36.0.0。固定 AGP 9.0.1（内置 Kotlin 2.2.10）、Gradle 9.1.0、Compose BOM 2026.01.00；Wrapper JAR 与 Gradle 发行包均有固定 SHA-256。此次 UI 迁移保持 minSdk 26、targetSdk 33，避免同时改变运行环境行为。

Windows 现有工具过旧时，可只在工程内准备工具链：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\prepare-windows-toolchain.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1 -PerformancePackage
```

JDK/SDK 放在 `tools/`，下载与 Gradle 缓存放在 `.cache/`；不安装到系统、不永久修改环境。复用现有兼容工具时，直接给构建脚本传 `-Jdk`、`-Sdk`。

Linux x86_64 已有兼容工具时：

```bash
JAVA_HOME=/path/to/jdk ANDROID_HOME=/path/to/sdk ./scripts/build.sh --performance-package
```

本项目开发仍遵守 Mac 不安装安卓工具链：本机编辑源码，Windows 或 GitHub Actions 构建。普通包 ID 为 `dev.plutocrown.factorio`；可选性能包 ID 为 `com.miHoYo.Yuanshen`。两个 APK 与 `build-result.json` 输出到 `artifacts/`。构建入口运行 Gradle 单元测试（原有检查及键盘请求/组合文字检查），再检查签名、对齐、包名，以及 APK 内52个原有运行库、XKB、1个AndroidX路径库及键盘请求程序的全部哈希。JNI注解stub仅编译期使用，不进入APK；Gradle不重建或剥离已校验的第三方运行库；小型键盘请求程序随构建从源码编译，需要工程内 Zig 0.15.2，准备方式见键盘说明。

两个入口均自动恢复固定运行库；也可单独执行（此恢复命令只需JDK11+）：

```bash
java scripts/RestoreRuntime.java
```

私有仓库需设置有 Contents: read 权限的 `GITHUB_TOKEN` 或 `GH_TOKEN`，不要把令牌写入源码或日志。离线恢复：`java scripts/RestoreRuntime.java --archive /path/to/factorio-android-runtime-arm64-20261021-v1.zip`；`--check` 仅验证；`--force` 明确替换已改动文件。依赖二进制不进Git，ZIP与53文件逐项校验。这是恢复预编译依赖，不等于从源码重建所有第三方组件。

签名复用工程内密钥或CI secrets；未配置则生成项目密钥，后续升级需保留同一签名。推送应用版本标签自动构建并上传Releases，`runtime-`标签保留给依赖附件。手动发布：Actions → Release → Run workflow，`release_tag`填新应用版本；留空仅构建并保存Actions附件。已有标签不移动。

## 许可证

自有启动器、原生适配、测试和构建脚本使用 [GPL-3.0-or-later](LICENSE)，版权归 Pluto_CRown（2026）。第三方组件保持各自的原文许可证，见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

这份授权不包含 Factorio 的名称、商标、引擎、游戏数据，也不包含 `factorio_icon.png` 和 `factory_background.jpg`。

## GitHub Star History

[![Star History Chart](https://api.star-history.com/svg?repos=PlutoCRown/FactorioAndroid&type=Date)](https://www.star-history.com/#PlutoCRown/FactorioAndroid&Date)
