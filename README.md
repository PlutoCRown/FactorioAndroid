# FactorioAndroid

在 Android ARM64 设备上独立运行用户自行导入的官方 **Linux ARM64 Factorio**。启动器提供游戏包导入、启动设置、设备检测、窗口、输入与音频适配；游戏引擎与资源保持原样，不随源码仓库提供。运行无需 Termux 应用、PRoot、root 或连接另一台电脑。

这是持续开发中的非官方个人迁移项目，与 Wube Software 无隶属关系。Factorio 名称、图标和游戏素材属于其权利人；使用者须自行取得正版游戏。现有本地图标和背景来源见第三方说明，个人测试构建不代表取得了这些素材的公开再分发许可。

## 当前状态

2026-10-21，直接 Zink 路线已在真实平板上显示完整游戏，并由用户确认流畅。已验证组合：

| 项目 | 已验证内容 |
| --- | --- |
| 游戏 | 官方基础版 Factorio 2.1.22，Linux ARM64 图形完整客户端 |
| 设备 | 小米 M367FC / XRing O3 / Mali-G2-Ultra-NX MC16，Android 17 |
| GPU 路径 | GNU Mesa26.3 Zink → Vulkan/Hybris ICD → Mali GPU AHardwareBuffer → 原生 Android Surface |
| 实测画面 | 1704×1136 渲染，3408×2272 全屏 Surface，截图单点60.1 FPS、Flip4.483ms |
| 颜色与声音 | 地形、水面、中文界面显示正常；用户确认音频可用 |
| 输入 | 触屏左键点击/按住拖拽，Android 返回对应 Esc；键盘和 SDL 手柄转发已接入 |

**高画质加载修复：** 已复现原高画质预设在贴图加载阶段以137退出，同时出现约5.1 GiB引擎内存峰值和系统内存回收。单项对照仅把贴图压缩从“无”改为“高质量”，保留高画质素材、8192图集和100%分辨率，约20秒完成初始化并进入存档，采样峰值约3.4 GiB；地面/水面正常，截图单点约59 FPS。当前“高画质”预设采用此压缩设置。没有取得引擎本身明确的LMKD杀因，不能把其137退出单独当作OOM结论；大存档、模组和其他设备仍可能有不同内存需求。

后台恢复黑屏已有 Surface 接收器生命周期修复：重建窗口时保留图像连接和 GLES context，仅替换 EGLSurface。加载中两次后台/桌面恢复已实测同进程接续，40秒后台引擎停止且CPU计数不变，恢复后主菜单正常。后台同时暂停音频；桌面图标优先恢复现有会话。游戏正常退出及本轮137异常退出均已自动回启动器并记录提示文字；Toast可见画面尚未单独取证，完整多窗口生命周期仍待验收。

该 FPS 来自一个实际游戏画面，不保证所有存档、设备或画质档位均达到60 FPS。物理手柄、多窗口实时 resize、长时间运行与其他游戏版本仍需进一步验收。

当前只保留直接 Zink → Android Surface 路线，不使用 VirGL，也不经过旧呈现路径的整帧 CPU 像素回读/传输。X11 仍用于游戏的窗口、输入事件和尺寸管理；图像通过共享 AHardwareBuffer 交给原生 Surface，保留一次 GPU 采样和正确性所需的同步等待。旧的 VirGL/ANGLE/Venus/软件游戏渲染入口及专用运行库已从应用移除，历史实现可在 Git 标签中查看。

## 获取并导入游戏

1. 登录 [Factorio 官网](https://factorio.com/)。游戏购自 Steam 时，可按[官方 FAQ](https://factorio.com/support/faq)在官网账户关联 Steam。
2. 打开[官方下载页](https://factorio.com/download)或[实验版下载页](https://factorio.com/download/experimental)，选择 **Linux ARM64 图形完整客户端 `.tar.xz`**。不要选择 Linux x64、headless、差分更新包或其他平台包。
3. 本项目当前只验证基础版2.1.22。Space Age 的完整包需要相应内容所有权，兼容性尚未在此组合上验收；基础包不包含 DLC。
4. 将原始 `.tar.xz` 放在 Android 可通过系统文件选择器访问的目录，在启动器主页点“选择游戏安装包”。不需提前解压。
5. 导入完成后进入“启动设置”，本机已验证画质组合为 **兼容起步＋50%清晰度**，再回主页点“启动游戏”。图形后端固定使用 **Zink · Android Surface**，旧版本保存的后端选择会自动清除，无需再选择后端。

已验证原包 `factorio_linux-arm64_2.1.22.tar.xz` SHA-256：

```text
5d6ff79eb190a29d4fb78b86d50fa28e099feffbc8b394c3d4f515a886ab5bf1
```

可与[官方校验清单](https://factorio.com/download/sha256sums/)核对。引擎哈希和 SDL 版本还有固定验证条件；成功导入另一个版本不代表能运行。

## 界面与操作

- 左侧图标栏依次为主页、启动设置、设备信息，长按可查看名称。未导入游戏时，仍可选择本体和检测设备。
- 宽窗口三个页面共用右侧背景；窄窗口隐藏装饰，保留可滚动的控制区域。
- 清晰度与画质两个下拉框支持点击整个输入框打开，设置在下次启动时应用；图形后端仅展示固定的新路线。
- 桌面应用名为 **Factorio**。游戏中的“会话”按钮位于**右下角**，可显示启动状态或结束会话并返回。状态浮层不占用游戏布局空间，加载后自动收起。
- Manifest通过 `android:appCategory="game"` 声明为游戏。厂商是否提供性能模式及如何调度仍由系统决定；该声明不保证额外内存或免于系统回收。
- 全屏时请求横屏和沉浸模式；分屏/小窗时尊重系统窗口。引擎实时 resize 的完整验收尚未完成。
- 单指按下、移动、抬起模拟鼠标左键拖拽；多指、取消或失焦会释放按键。Android 返回键发送 Esc。

## 游戏数据与升级

导入包展开到应用私有游戏实例；游戏资源从实例的 `factorio/data` 读取。正式档案使用：

```text
files/profiles/default-2.1.22/config.ini
files/profiles/default-2.1.22/userdata/saves/
files/profiles/default-2.1.22/userdata/mods/
```

APK 覆盖升级保留导入、设置和存档。卸载或清除应用数据会删除私有文件；独立存档/模组导入导出界面尚未实现。启动前只合并所选分辨率和画质键，语言及其他游戏设置保留。“使用游戏内设置”停止覆盖画质预设；如果旧配置仍是高画质＋无贴图压缩，可先使用一次新版“高画质”，或在游戏中启用高质量贴图压缩。此模式不会擅自覆盖用户主动关闭压缩的配置。

## 源码与构建

此目录是完整 Android 启动器工程：`app/` 为应用源码/资源和运行库清单，`native-input/` 为原生适配层，`tests/` 为现有逻辑检查，`build-windows.ps1` 为构建入口。直接 Surface 的公开 ICD 来源与本地修改记录在 `native-input/direct-wsi/sources.json`。

Android 构建在 Windows 完成；Mac 只编辑和同步源码。当前复用 Android SDK Platform33 / Build Tools33.0.1、JDK15.0.2，以及项目内 Zig0.15.2。应用不需要 Gradle 或 Android Studio 来执行这份构建脚本。

```powershell
# 在已经准备好运行库、SDK 和 JDK 的工程目录执行。
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-windows.ps1 `
  -Sdk 'D:\Android\Sdk' -Jdk 'D:\Java\jdk-15.0.2'
```

输出：`artifacts/FactorioLauncher-0.2.0-runtime-probe-debug.apk`。这是开发签名包；覆盖升级须使用同一签名密钥。密钥保存在本地 `.local/`，不得提交仓库。

**源码检出暂不等于一键可构建**：预编译 JNI 运行库、XKB 归档、诊断工具、SDK、项目内编译器和签名密钥不在 Git 中。构建前必须按 `app/src/main/assets/*runtime*.json`、`direct-zink.json`、`direct-dependencies.json` 等清单恢复哈希匹配的载荷到 `app/src/main/jniLibs/arm64-v8a/` 与指定 assets。已有工程另有本地工作区标记 `.factorio-migration-workspace.json`；干净的可信工程可用内容 `{}` 创建此标记。原生重建还需要固定头文件、GNU/Bionic 链接输入。自动恢复整套依赖的便携构建流程尚待整理，不能把本地构建成功称为干净克隆即可复现。

构建执行103项逻辑检查（24归档、43配置/语言、13实时图形配置、19触屏/尺寸、4导入互斥），再验证签名、对齐和包信息。原生层在需要修改时单独构建，UI 改动保留已验收 GPU 库。

`scripts/build-direct-present.ps1` 和 `scripts/build-direct-wsi.ps1` 保留自有原生层的实际编译命令，仍依赖已准备好的项目内 Zig、头文件与 GNU/Bionic 链接输入，不会自动安装系统工具链。运行这些脚本后须将产物哈希同步到对应运行库清单，再打包。

故障定位时保留 `files/game-session-report.json`、`files/game-session-engine.json`（包含引擎输出的开头和末尾）、`files/direct-present-report.txt` 和游戏当前日志。API30+ 下次打开启动器还会更新 `files/last-process-exits.json`，记录本包的系统进程退出原因、状态及 PSS/RSS；系统不一定为 GNU 引擎子进程单独提供这项记录，缺记录不能当作没有崩溃。

## 来源、许可证与发布范围

第三方组件说明见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) 和 APK 内各组件许可证；每个组件仍遵循其原始许可证，项目未给第三方代码重新授权。直接路线涉及 Mesa、Vulkan Loader、libhybris、Termux glibc 和 Termux:X11 等公开组件。源码仓库不提供 Factorio 引擎、游戏安装包、存档、账号或系统 GPU 驱动。

此 GitHub 仓库只包含当前安卓工程的独立源码树和声明，不含开发研究仓库的历史、设备日志、网络连接配置、账号凭据或游戏安装包。当前为个人使用的私有仓库；本地图标/背景的保留不代表取得其公开分发授权。项目自有代码尚未指定对外开源许可证；第三方组件的原许可证继续适用。
