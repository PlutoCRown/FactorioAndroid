![Factorio Android Launcher](docs/images/factorio-cover.png)

# Factorio Android Launcher

<p align="center">
  <a href="https://github.com/PlutoCRown/FactorioAndroid/actions/workflows/release.yml">
    <img src="https://img.shields.io/github/actions/workflow/status/PlutoCRown/FactorioAndroid/release.yml?label=build%20%26%20tests&logo=github" alt="Build &amp; tests"/>
  </a>
  <a href="https://github.com/PlutoCRown/FactorioAndroid/releases/latest">
    <img src="https://img.shields.io/github/v/release/PlutoCRown/FactorioAndroid?logo=github" alt="Latest release"/>
  </a>
  <a href="https://github.com/PlutoCRown/FactorioAndroid/releases">
    <img src="https://img.shields.io/github/downloads/PlutoCRown/FactorioAndroid/total?logo=github" alt="Downloads"/>
  </a>
  <a href="https://github.com/PlutoCRown/FactorioAndroid/stargazers">
    <img src="https://img.shields.io/github/stars/PlutoCRown/FactorioAndroid?style=flat&logo=github" alt="GitHub stars"/>
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg" alt="GPL-3.0-or-later"/>
  </a>
</p>

<p align="center">
  <strong>简体中文</strong> | <a href="README.en.md">English</a>
</p>

在 Android 上运行你自己的官方 Linux ARM64 Factorio。这是非官方启动器，与 Wube Software 无关。

## 项目说明

### 简介

本启动器通过 GNU/Linux 运行环境、Mesa Zink 和设备的 Vulkan 驱动，将官方 Linux ARM64 图形客户端的画面呈现到 Android Surface。

> 本项目不包含、也不分发 Factorio 的原版游戏文件。你需要在官网下载并持有游戏资源（建议通过官网账号购买和下载）。将游戏安装包导入后，即可通过启动器进行启动和游玩。

### 实现功能

- 完整运行 Linux ARM64 图形完整客户端，兼容原版和太空时代 DLC。
- 0 CPU 转译损耗，主流芯片轻松运行后期工厂。
- 存档导入和图形设置选项，存档与 Mod 各端通用。
- 转发键盘、鼠标与触屏模拟鼠标输入；屏幕虚拟手柄支持。

## 食用指南

1. 打开 [Releases](https://github.com/PlutoCRown/FactorioAndroid/releases)，下载并安装本应用。选择所需版本的标准 APK。
2. 登录 [Factorio 官网](https://factorio.com/)。如果你的游戏购买于 Steam，按 [官方 FAQ](https://factorio.com/support/faq) 把 Steam 关联到官网账号。
3. 在 [官方下载页](https://factorio.com/download) 选择 **Linux ARM64 版**（如果没有这个版本，可以切换到旁边的实验版）。想玩太空时代只需下载太空时代包即可，无需再下载原版。
4. 把原始压缩包放到手机能用文件选择器打开的目录。在启动器主页点「选择游戏安装包」，不需要提前解压。
5. 点「启动游戏」。

## 运行要求

- 需要 64 位 ARM Android，系统版本至少为 8.0（API 26）。
- 内存：游玩原版建议使用 12 GB 内存设备（至少 8 GB），游玩太空时代 DLC 建议使用 16 GB 内存设备（至少 12 GB）。
- 存储：原版建议预留 4 GB 存储空间，太空时代 DLC 建议预留 8 GB 存储空间。
- CPU：建议使用骁龙 8 Gen 1 以上或同等性能处理器。

实际测试数据：
> 测试存档：太空时代 DLC 解锁四星球，除黑瓶外 1,000 SPM 的存档。
>
> - Redmi K70（骁龙 8 Gen 2），渲染分辨率 2400×1080：60 UPS，FPS 随视野缩放在 20–40 波动。
> - Xiaomi Pad 9 Pro Max（玄戒 O3），渲染分辨率 2400×1080：60 UPS，FPS 随视野缩放在 40–60 波动。
>
> 感谢用户提交的使用情况，项目已知在以下的设备上正常运行：
> Redmi K50 （天玑 8100）、Realme GT 7 Pro （骁龙 8E）
> 
> 欢迎在 [Issues](https://github.com/PlutoCRown/FactorioAndroid/issues) 提交你的测试数据。

## 操作说明与配置指南

1. 建议自备外接键鼠或手柄，当然该项目也提供虚拟屏幕手柄。
2. 如在游玩中遇到闪退，多半是内存瓶颈。建议在启动前将画质预设为 4K 贴图，并限制贴图加载线程上限（建议设为最低，这会使游戏加载变得稍慢，但不影响游玩）。调短自动存档间隔以防不测，~~或者氪金购买 24 GB 以上内存的设备~~。常见闪退场景：

   - 游戏启动过程中 50%–90% 的阶段。
   - 点击创建地图。
   - 在远程视图中切换星球／飞船表面。

3. 游戏不支持同时使用键盘和手柄输入，如果接入多种设备，请在游戏内控制选项里面选择输入设备。

   - 若启用虚拟手柄，进入游戏会默认选中手柄作为输入设备。
   - 若使用键盘输入，并且启用了触屏鼠标模拟，你可以双指上下滑动来滚动容器。
   - 你可以使用安卓侧滑返回来代替键盘输入 Esc，特别是遇到一些大窗口无法点击关闭的时候。
   - 当聚焦到输入框时，设备会弹出输入法。关闭弹出的输入法后，才能继续使用触屏操作。

## 开发指南

> 项目是作者许愿式编程完成，远未达到最优运行效率，欢迎提交你的贡献。

源码分层、目录职责、格式化命令和测试入口见 [工程结构与开发约定](docs/DEVELOPMENT.md)。图形兼容运行库的源码、补丁、构建和复用步骤见 [Mesa 兼容构建](docs/MESA-COMPATIBILITY.md)。

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
