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

macOS 和 Linux 用 `scripts/build.sh`，Windows 用 `scripts/build-windows.ps1`。构建不使用 Gradle。

先在项目根目录恢复固定版本的运行库和 XKB（需要 JDK11+，使用构建已有的 JDK 即可）：

```bash
java scripts/RestoreRuntime.java
```

Windows 若 Java 不在 PATH，可使用已有 JDK 的完整路径：

```powershell
& 'D:\Java\jdk-15.0.2\bin\java.exe' .\scripts\RestoreRuntime.java
```

该命令下载 Release 中的固定依赖包，校验 ZIP 及每个文件的 SHA-256，恢复52个原生库和 `window-xkb.zip`。已完整恢复时直接校验并跳过下载；不会执行下载的原生程序或改系统环境。依赖二进制不进入 Git。当前仓库为私有时，需要先在本地设置有本仓库 Contents: read 权限的 `GITHUB_TOKEN`（或 `GH_TOKEN`）；不要把令牌写入脚本、提交或发给他人。公开仓库无需令牌。

也可在浏览器下载依赖包后离线恢复：`java scripts/RestoreRuntime.java --archive /path/to/factorio-android-runtime-arm64-20261021-v1.zip`。`--check` 仅验证现有文件；现有文件被改动时默认拒绝覆盖，需要明确使用 `--force` 才恢复为固定版本。这是恢复经过验证的预编译依赖，不等于从源码重新编译所有第三方组件。

```bash
./scripts/build.sh
./scripts/build.sh --performance-package
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1 `
  -Sdk 'D:\Android\Sdk' -Jdk 'D:\Java\jdk-15.0.2'
```

普通包的应用 ID 是 `dev.plutocrown.factorio`，输出在 `artifacts/`。`--performance-package` 会再用应用 ID `com.miHoYo.Yuanshen` 打一份包，让部分手机套用该游戏的性能调度；界面名称仍是 Factorio。

两个构建入口都会先运行上述恢复工具，缺少依赖且无法下载时会直接失败，避免生成没有运行环境的 APK。下载缓存在项目的 `.cache/runtime/`。仍需自行准备 Android SDK 和 JDK；签名密钥从本地或 CI secrets 读取，未提供时使用项目内生成的密钥。推送应用 Git 标签会由 `.github/workflows/release.yml` 构建并上传 APK；`runtime-` 前缀标签仅用于固定依赖附件，不运行应用发布任务。

## 许可证

自有启动器、原生适配、测试和构建脚本使用 [GPL-3.0-or-later](LICENSE)，版权归 Pluto_CRown（2026）。第三方组件保持各自的原文许可证，见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

这份授权不包含 Factorio 的名称、商标、引擎、游戏数据，也不包含 `factorio_icon.png` 和 `factory_background.jpg`。

## GitHub Star History

[![Star History Chart](https://api.star-history.com/svg?repos=PlutoCRown/FactorioAndroid&type=Date)](https://www.star-history.com/#PlutoCRown/FactorioAndroid&Date)
