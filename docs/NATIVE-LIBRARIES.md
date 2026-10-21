# jniLibs 原生库来源、用途与复现说明

记录日期：2026-10-21。适用基线：`codex/compose-launcher`，运行库 `20261021-v1`。本文以当前工程清单、已保存的下载记录与本地归档为依据；没有在本次文档整理中重新联网下载或重新构建。上游 URL 记录的是当时取得文件的位置，不承诺今后一直有效。

## 1. 这些文件属于什么

`app/src/main/jniLibs/arm64-v8a/` 中的 52 个 `.so` 是随启动器 APK 附带的 ARM64 原生运行环境。其中 48 个直接提取自上游预编译包，4 个由本项目编译（包括基于上游源码适配的组件）。它们承担 Linux 基础运行库、图形兼容、窗口、输入和 Android 呈现，不包含 Factorio 引擎或游戏数据。

Kotlin/Java 启动器负责界面、导入、配置和会话管理；用户自行导入的官方 Linux ARM64 包才提供游戏。`.so` 放在 `jniLibs` 只是打包位置，并非全部通过 JNI 加载进 Java/ART。glibc、Mesa、GNU 图形依赖供独立引擎进程使用；`libXlorie.so` 和自有呈现接收器在 Android/Bionic 侧运行。

`rea` 是项目私有命名前缀，不表示源码作者。`librea_gl_<十位哈希>.so` / `librea_egl_<十位哈希>.so` 的后缀来自原库名的 SHA-256 前十位，用于统一打包命名；完整文件 SHA-256 见附录。启动时通过受校验的运行库清单建立原名别名，例如 `librea_glibc.so` → `libc.so.6`。

Compose 另由 Gradle 的 AndroidX 依赖带入 `libandroidx.graphics.path.so`，因此当前 Compose APK 共 53 个 `.so`。它不在上述手动恢复的 52 个库中。恢复清单另含 `window-xkb.zip`，所以“52 个库 + 1 个 XKB 文件 = 53 个恢复项”和“Compose APK 的 53 个库”是两个口径。

简化运行链路：

```text
用户导入的 Linux ARM64 Factorio
  → GNU loader / glibc + GLVND / Mesa Zink
  → GNU Vulkan Loader + 本项目 ICD / libhybris
  → 设备自带 Android Vulkan / GPU 驱动
  → GPU AHardwareBuffer → 本项目 Bionic 接收器 → Android Surface

X11：保留窗口协议与输入；当前主呈现链路不通过 X11 传整帧像素。
```

## 2. 最可靠的复现：恢复当前完全相同的文件

日常构建推荐恢复项目固定 ZIP，而非分别向上游索取“最新版”。构建入口会自动运行恢复器；单独恢复只需 JDK 11+，不要求 Android SDK/NDK/ADB。本项目安卓构建仍在 Windows 内进行，Mac 不安装安卓工具链。

从安卓工程根目录（本研究仓库中为 `launcher/`，独立安卓仓库中为仓库根目录）执行：

```powershell
java scripts/RestoreRuntime.java
java scripts/RestoreRuntime.java --check
```

固定附件：[runtime-20261021-v1 Release](https://github.com/PlutoCRown/FactorioAndroid/releases/tag/runtime-20261021-v1)。

- [直接下载 ZIP](https://github.com/PlutoCRown/FactorioAndroid/releases/download/runtime-20261021-v1/factorio-android-runtime-arm64-20261021-v1.zip)
- [GitHub 附件 API，asset ID 661557624](https://api.github.com/repos/PlutoCRown/FactorioAndroid/releases/assets/661557624)
- 文件名：`factorio-android-runtime-arm64-20261021-v1.zip`
- 大小：`22155706` 字节。
- SHA-256：`42022385585d71558c835589def3cea3a50efbc0782a0e13aedad7fd0146297e`。

仓库为私有时，需先由自己在当前进程环境中设置有 Contents: read 权限的 `GITHUB_TOKEN` 或 `GH_TOKEN`；恢复器仅向 GitHub API 发送令牌。也可登录 GitHub，在 Release 页面手动下载后离线恢复：

```powershell
Get-FileHash -Algorithm SHA256 C:\Downloads\factorio-android-runtime-arm64-20261021-v1.zip
java scripts/RestoreRuntime.java --archive C:\Downloads\factorio-android-runtime-arm64-20261021-v1.zip
java scripts/RestoreRuntime.java --check
```

恢复器先验证整个 ZIP，再验证每个文件的大小和 SHA-256，放入清单规定的位置。已存在但内容不同的文件默认拒绝覆盖；确认需要恢复固定版本时才添加 `--force`。下载缓存位于工程 `.cache/runtime/`，不是系统级安装。当前下载实现不提供断点续传；网络不佳时可用支持续传的工具取得完整 ZIP，再走 `--archive`。

固定信息在 [runtime-payload.properties](../scripts/runtime-payload.properties)，逐文件校验在 [runtime-files.tsv](../scripts/runtime-files.tsv)，恢复实现为 [RestoreRuntime.java](../scripts/RestoreRuntime.java)。ZIP 内有 52 个库、XKB 和来源/许可证文字，不含游戏、账号凭据或设备 GPU 驱动。

此路线已在先前完成 Mac 下载/离线校验及 Windows 干净源码恢复后构建验证。本次仅增加文档并核对现有文件，不把历史验证描述为本次重新构建。

## 3. 按上游来源重新取得预编译文件

本节用于解释每个库最初从哪里来。它可以复现下载和提取，不等同于从全部上游源码重新编译，也不承诺不同编译环境生成字节一致的文件。

下载后按本节记录的包大小和 SHA-256 验证，再按第 4 节提取指定成员、重命名，最后与附录的单库 SHA-256 对比。仅下载和解包文件，不运行 DEB 安装脚本、capsule 中的工具或上游构建脚本，也无需安装 Termux 应用。

### 3.1 Termux DEB 来源

以下为当前仍使用的精确包版本。多数包来自 Termux 的 glibc 仓库；`ndk-sysroot` 是第 5 节才需要的编译头文件输入，不计入 52 个库。上游包仓库可能删除旧版本；遇到 404 或哈希不符，使用第 2 节固定 ZIP，不擅自替换为最新版。历史记录检查了 HTTPS 取得内容及包/单文件哈希，未完成 DEB 仓库签名验证。

| 来源 ID / 包 | 版本 | 精确下载地址 | 字节 | 包 SHA-256 |
| --- | --- | --- | --- | --- |
| `gcc-libs-glibc` | 14.2.1-1 | [gcc-libs-glibc_14.2.1-1_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/g/gcc-libs-glibc/gcc-libs-glibc_14.2.1-1_aarch64.deb) | 24314568 | `919c2e80a629d674dd450b0a0edd11378284f1ed9d2a8fd3c9c91d7ac72f041f` |
| `glibc` | 2.44 | [glibc_2.44_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/g/glibc/glibc_2.44_aarch64.deb) | 9813364 | `5392d254e84c436e96783bcb6ffffe429d6b24c7ad00490f23f06f66c4769fb6` |
| `libdrm-glibc` | 2.4.134 | [libdrm-glibc_2.4.134_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libd/libdrm-glibc/libdrm-glibc_2.4.134_aarch64.deb) | 341204 | `533be512b02e7c6523ad4bef5ade405d53b4b3d364819f52522602f389e52957` |
| `libexpat-glibc` | 2.8.4 | [libexpat-glibc_2.8.4_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libe/libexpat-glibc/libexpat-glibc_2.8.4_aarch64.deb) | 121204 | `496f29de09f46597ca21ddddfec1c20ce4ff75766af50379527ddf2aa33e4896` |
| `libffi-glibc` | 3.8.0 | [libffi-glibc_3.8.0_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libf/libffi-glibc/libffi-glibc_3.8.0_aarch64.deb) | 42236 | `21317646531fcd63e8fa005e2b3de55947781a203f8d9206ce9348ec947fdb63` |
| `libglvnd-glibc` | 1.7.0-1 | [libglvnd-glibc_1.7.0-1_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libg/libglvnd-glibc/libglvnd-glibc_1.7.0-1_aarch64.deb) | 320536 | `da0a0943e40c05f3ed29e5391e4a106717bb495bc55adf6a7b7b036e62bf1240` |
| `libwayland-glibc` | 1.23.1 | [libwayland-glibc_1.23.1_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libw/libwayland-glibc/libwayland-glibc_1.23.1_aarch64.deb) | 133428 | `dedc307a6a818b028343e00eb8465a80e990f3d5ec93a37fc28f86f814673e49` |
| `libx11-glibc` | 1.8.11 | [libx11-glibc_1.8.11_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libx11-glibc/libx11-glibc_1.8.11_aarch64.deb) | 1984312 | `a0c249dd82f5bcf46d61501a436fcfc2e317869a06913c4b8488c9d3d19dca1a` |
| `libxau-glibc` | 1.0.12 | [libxau-glibc_1.0.12_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxau-glibc/libxau-glibc_1.0.12_aarch64.deb) | 10528 | `7f2c0624638feed515899e3acbb1ec475ba97c9442b5a276dabaea652d9db62c` |
| `libxcb-glibc` | 1.17.0-1 | [libxcb-glibc_1.17.0-1_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxcb-glibc/libxcb-glibc_1.17.0-1_aarch64.deb) | 952208 | `5e737e2bb57a0a1919be298b5f60c8a6b8f9cc79225f1b90c566bcdba73133a0` |
| `libxcursor-glibc` | 1.2.3 | [libxcursor-glibc_1.2.3_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxcursor-glibc/libxcursor-glibc_1.2.3_aarch64.deb) | 31976 | `7901dd136016df9d694e38bb17923812f539241475e7ac97935c8dcdfcceb902` |
| `libxdmcp-glibc` | 1.1.5 | [libxdmcp-glibc_1.1.5_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxdmcp-glibc/libxdmcp-glibc_1.1.5_aarch64.deb) | 25556 | `b07513746d141bb11ff2c5cd496c0131da3ddbdb24bf92d51635d6b3c93ad1fb` |
| `libxext-glibc` | 1.3.6 | [libxext-glibc_1.3.6_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxext-glibc/libxext-glibc_1.3.6_aarch64.deb) | 101604 | `09a989f92535f27343bf9d8904bf43e7a8a7827f5ef910eed45d112ad5bf0590` |
| `libxfixes-glibc` | 6.0.1 | [libxfixes-glibc_6.0.1_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxfixes-glibc/libxfixes-glibc_6.0.1_aarch64.deb) | 12832 | `23fe7f6003d9607db6af5c31b995616270c319f2af11ddcd6292facd43b25b66` |
| `libxi-glibc` | 1.8.2 | [libxi-glibc_1.8.2_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxi-glibc/libxi-glibc_1.8.2_aarch64.deb) | 147052 | `716705064cc53d3237a377fe4b0620550ed9600417fca5574634e76e2381c295` |
| `libxrandr-glibc` | 1.5.4 | [libxrandr-glibc_1.5.4_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxrandr-glibc/libxrandr-glibc_1.5.4_aarch64.deb) | 25140 | `2e403fc0d8f319933866f63478148f436757c801c5162362d3aa6732d7a567b8` |
| `libxrender-glibc` | 0.9.12 | [libxrender-glibc_0.9.12_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxrender-glibc/libxrender-glibc_0.9.12_aarch64.deb) | 26984 | `86b181007e83f8055f77b964aa77dce2fd48949c04b5d5f1f7be9f8c83cfab52` |
| `libxshmfence-glibc` | 1.3.3 | [libxshmfence-glibc_1.3.3_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxshmfence-glibc/libxshmfence-glibc_1.3.3_aarch64.deb) | 5904 | `dca9418b1c3bd24b7bb47261eda17a40646c7f4fb903b10c77a9e6c08d38c1c7` |
| `libxxf86vm-glibc` | 1.1.6 | [libxxf86vm-glibc_1.1.6_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxxf86vm-glibc/libxxf86vm-glibc_1.1.6_aarch64.deb) | 13688 | `407a13753a6a3162465d7bd2618b217e9dba52a274f3c4c201db34c0012352a3` |
| `vulkan-icd-loader-glibc` | 1.3.301 | [vulkan-icd-loader-glibc_1.3.301_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/v/vulkan-icd-loader-glibc/vulkan-icd-loader-glibc_1.3.301_aarch64.deb) | 141772 | `9d1defb2ab4fb4b79858ed5128a14df15e526fb98cb4f51033304b0e3b765b59` |
| `xkeyboard-config-glibc` | 2.43 | [xkeyboard-config-glibc_2.43_all.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/x/xkeyboard-config-glibc/xkeyboard-config-glibc_2.43_all.deb) | 792008 | `2ef4a50e2f5840a12b9e5843334ff63c410ddaf5a7461aa27abeb2bffdb7a2bc` |
| `zlib-glibc` | 1.3.2 | [zlib-glibc_1.3.2_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/z/zlib-glibc/zlib-glibc_1.3.2_aarch64.deb) | 77640 | `825d36673420387a31683deb93529bd8cb8fbd6328f061a9a26f779e016aef17` |

DEB 是 `ar` 容器，文件内容在内部 `data.tar.*` 中。路径 `data/data/com.termux/files/usr/glibc/lib/` 是归档里的历史前缀，不代表必须把应用装成 Termux 或写入 Termux 目录。例子（PowerShell，`tar.exe` 需支持所选 DEB 的压缩格式）：

```powershell
# URL、大小与 SHA 请使用上表对应行。
curl.exe --fail --location --continue-at - --output libglvnd-glibc_1.7.0-1_aarch64.deb https://packages.termux.dev/apt/termux-glibc/pool/stable/libg/libglvnd-glibc/libglvnd-glibc_1.7.0-1_aarch64.deb
Get-FileHash -Algorithm SHA256 .\libglvnd-glibc_1.7.0-1_aarch64.deb
# 先人工核对大小/哈希，再解包。目录均放在工程内临时工作区。
New-Item -ItemType Directory -Force .\work\glvnd | Out-Null
tar.exe -tf .\libglvnd-glibc_1.7.0-1_aarch64.deb
# 根据上一条实际列出的 data.tar.xz / data.tar.gz 等名称提取内层归档：
tar.exe -xf .\libglvnd-glibc_1.7.0-1_aarch64.deb -C .\work\glvnd data.tar.xz
tar.exe -tf .\work\glvnd\data.tar.xz
# 例如只取 libGL.so.1；若清单中的成员以 ./ 开头，保留实际前缀。
tar.exe -xf .\work\glvnd\data.tar.xz -C .\work\glvnd data/data/com.termux/files/usr/glibc/lib/libGL.so.1
Get-FileHash -Algorithm SHA256 .\work\glvnd\data\data\com.termux\files\usr\glibc\lib\libGL.so.1
# 与附录对应行核对后，再按打包名放入工程：
Copy-Item .\work\glvnd\data\data\com.termux\files\usr\glibc\lib\libGL.so.1 .\app\src\main\jniLibs\arm64-v8a\librea_gl_5a8cfeda9f.so
```

上例仅示范解包步骤，不作为本次已运行的 Windows 复现证据。不同包的压缩格式可能不同，不要把 `data.tar.xz` 写死成通用值；不支持格式时，直接用已验证的项目 ZIP 恢复。

### 3.2 Mesa 26.3.0-devel capsule

上游项目：[taowen/mesa](https://github.com/taowen/mesa)。[下载 Release](https://github.com/taowen/mesa/releases/tag/arlinux-linux-mesa-debug) 的标签为 `arlinux-linux-mesa-debug`，历史 Release ID 为 `418643105`。这是可更新的标签，必须以内容哈希确认。

- [精确 capsule 下载](https://github.com/taowen/mesa/releases/download/arlinux-linux-mesa-debug/capsule-9c1f55dbd8838304013b86312706e2983e9fc0d0abfe01a803404d0745faf666-000.zst.part)
- 文件名：`capsule-9c1f55dbd8838304013b86312706e2983e9fc0d0abfe01a803404d0745faf666-000.zst.part`
- 字节：`10202719`；SHA-256：`9c1f55dbd8838304013b86312706e2983e9fc0d0abfe01a803404d0745faf666`。

- [配套 component.json](https://github.com/taowen/mesa/releases/download/arlinux-linux-mesa-debug/component.json)：938 字节，SHA-256 `63ed24c71bfc2080ef1aa977e2a31cdbdd946047873b3c8a1f3daa85478b9a19`。
- capsule API：[asset 659911125](https://api.github.com/repos/taowen/mesa/releases/assets/659911125)；元数据 API：[asset 659911185](https://api.github.com/repos/taowen/mesa/releases/assets/659911185)。
- `component.json` 中 `sourceInputs.sourceSha256` 为 `2248ea037d5dd376bb9c667080f0be9d1fcae2a711c53f6f611cc4cd4f190b36`，这是上游组件的源码输入摘要，不是 Git commit。

当前记录只有一个 `.zst.part`，它本身就是可解压的 zstd TAR；无需执行专用安装器。使用支持 zstd 的 tar 列目录，再只提取第 4 节列出的六个成员。它们都在 `framework/build/linux/mesa/lib/` 下。`dri/zink_dri.so` 可能是别名；本项目实际保存的字节来自 `dri/libdril_dri.so`，启动时再创建 `dri/zink_dri.so` 别名。

示例：下载并核对整个 capsule 后，提取 Gallium 主库（其余五个按第 4 节路径同样处理）：

```powershell
$mesaCapsule = '.\capsule-9c1f55dbd8838304013b86312706e2983e9fc0d0abfe01a803404d0745faf666-000.zst.part'
Get-FileHash -Algorithm SHA256 $mesaCapsule
tar.exe -tf $mesaCapsule
New-Item -ItemType Directory -Force .\work\mesa26 | Out-Null
tar.exe -xf $mesaCapsule -C .\work\mesa26 framework/build/linux/mesa/lib/libgallium-26.3.0-devel.so
Get-FileHash -Algorithm SHA256 .\work\mesa26\framework\build\linux\mesa\lib\libgallium-26.3.0-devel.so
Copy-Item .\work\mesa26\framework\build\linux\mesa\lib\libgallium-26.3.0-devel.so .\app\src\main\jniLibs\arm64-v8a\librea_direct_mesa_gallium.so
```

这些命令要求 `tar.exe` 支持 zstd，不要求执行 capsule 内的任何工具。若主机缺少解压支持，使用第 2 节的普通 ZIP。

曾检查的源码提交是 `ba042a8e79513c87006f4dd2dc15103724c26cfc`，尚未证实它与这份预编译 capsule 准确对应，不能据此承诺源码重建相同二进制。

### 3.3 libhybris capsule

上游：[taowen/libhybris](https://github.com/taowen/libhybris)。[固定身份 Release](https://github.com/taowen/libhybris/releases/tag/arlinux-linux-hybris-fe51c711ae44676f7ef5c73c)，标签 `arlinux-linux-hybris-fe51c711ae44676f7ef5c73c`。

- [capsule-000.zst.part](https://github.com/taowen/libhybris/releases/download/arlinux-linux-hybris-fe51c711ae44676f7ef5c73c/capsule-000.zst.part)：`3790524` 字节，SHA-256 `27ffd237e8a73bf852620b3d737df2e7a8d58b7e7de4d9788c59436a4a8f8d6e`。

- [配套 component.json](https://github.com/taowen/libhybris/releases/download/arlinux-linux-hybris-fe51c711ae44676f7ef5c73c/component.json)：1206 字节，SHA-256 `bd5f5de0eb6b2d918a851116fbf6dbe600517334393f841c801e242953da4b60`。
- `sourceInputs.sourceSha256`：`5aa78287ff744c10239b25e8e84897f7e5d9a529a829a74db8ea56f852825d6e`，不是 Git commit。

同样是单个 zstd TAR。只提取第 4 节的五个文件；共同前缀为 `framework/build/linux/libhybris/install/usr/lib/hybris/`。本项目没有直接打包这个 capsule 里的全部库。例如它的旧 Vulkan 桥 `libvulkan.so.1.2.183` 不等于当前 `librea_vulkan_loader.so`；当前 Vulkan Loader 取自上表 Termux 的 `vulkan-icd-loader-glibc_1.3.301_aarch64.deb`。原始上游 ICD 也由本项目源码构建版本替代。

检查/取用公开源码时固定的 libhybris 提交为 `2375acd0b5d278a0a2f745c645680545e8f80539`，见 [该提交](https://github.com/taowen/libhybris/tree/2375acd0b5d278a0a2f745c645680545e8f80539)。对上游预编译的 common/linker 等组件，尚未证明该提交就是其准确构建输入；第 5 节自编 ICD 的输入则另有逐文件记录。

### 3.4 Termux:X11 APK

上游：[termux/termux-x11](https://github.com/termux/termux-x11)，[nightly Release](https://github.com/termux/termux-x11/releases/tag/nightly)。

- [精确 APK 下载](https://github.com/termux/termux-x11/releases/download/nightly/termux-x11-universal-debug.apk)
- `termux-x11-universal-debug.apk`，`14693944` 字节；SHA-256 `64995746d1887a0e70bc8977d4a9c5ea56e3e00eadd4a6eeab32ae6048ba733a`。

- 历史 asset ID：`616219007`（以保存的 Release 元数据为准，直接下载 URL 和哈希是主要定位方式）。
- 只把 APK 当 ZIP 读取 `lib/arm64-v8a/libXlorie.so`，复制为本工程的同名文件，不安装那个 APK。
- 单库为 3552072 字节，SHA-256 `8ad76408710c21b9a61d3db07900e5bb4e3f88efb1670ac0ff3a2166f403567d`。
- 保存的 2026-10-06 Release 说明写明 “Based on fa3a8b430e2896a19f44c99a9cb056254615ae06”；本项目对 Java/JNI 窗口输入接口的源码检查也固定在[这个提交](https://github.com/termux/termux-x11/tree/fa3a8b430e2896a19f44c99a9cb056254615ae06)。这是上游发布说明及源码检查记录，未在本项目从该提交重建并比较 APK。

`nightly` 会被覆盖。若下载内容哈希改变，不能继续当作当前库使用；第 2 节的项目 ZIP 保存了已经验证的那一份。

已核对完整 APK 哈希后，可以使用支持 ZIP 的 `tar.exe` 提取（无需安装 APK）：

```powershell
New-Item -ItemType Directory -Force .\work\xlorie | Out-Null
tar.exe -xf .\termux-x11-universal-debug.apk -C .\work\xlorie lib/arm64-v8a/libXlorie.so
Get-FileHash -Algorithm SHA256 .\work\xlorie\lib\arm64-v8a\libXlorie.so
Copy-Item .\work\xlorie\lib\arm64-v8a\libXlorie.so .\app\src\main\jniLibs\arm64-v8a\libXlorie.so
```

## 4. 52 个库逐项对应表

来源 ID 与第 3 节一致。DEB 成员路径均相对于内层 `data.tar.*`；capsule 成员路径相对于 zstd TAR 根。每行列的是实际内容所在位置，不要求在设备上重建这些上游安装目录。大小和完整 SHA-256 在附录 A。

### 4.1 GNU 基础库

| APK 内库名 | 来源 ID / 取得方式 | 原包成员或源码位置 | 用途 |
| --- | --- | --- | --- |
| `librea_loader.so` | `glibc` | `data/data/com.termux/files/usr/glibc/lib/ld-linux-aarch64.so.1` | 启动独立 GNU/Linux 引擎进程的动态加载器 |
| `librea_glibc.so` | `glibc` | `data/data/com.termux/files/usr/glibc/lib/libc.so.6` | GNU C 运行库：线程、文件、内存及系统调用等 |
| `librea_glibm.so` | `glibc` | `data/data/com.termux/files/usr/glibc/lib/libm.so.6` | GNU 数学函数库 |
| `librea_resolv.so` | `glibc` | `data/data/com.termux/files/usr/glibc/lib/libresolv.so.2` | GNU DNS/解析接口支持 |

### 4.2 Mesa 与 Vulkan / Android 桥

| APK 内库名 | 来源 ID / 取得方式 | 原包成员或源码位置 | 用途 |
| --- | --- | --- | --- |
| `librea_direct_mesa_dril.so` | `mesa26` | `framework/build/linux/mesa/lib/dri/libdril_dri.so` | DRI 驱动加载入口；运行时别名为 dri/zink_dri.so |
| `librea_direct_mesa_egl.so` | `mesa26` | `framework/build/linux/mesa/lib/libEGL_mesa.so.0.0.0` | Mesa EGL 上下文、Surface 和交换实现 |
| `librea_direct_mesa_gallium.so` | `mesa26` | `framework/build/linux/mesa/lib/libgallium-26.3.0-devel.so` | Mesa Gallium/Zink 主实现，将桌面 OpenGL 转成 Vulkan |
| `librea_direct_mesa_gbm.so` | `mesa26` | `framework/build/linux/mesa/lib/libgbm.so.1.0.0` | Mesa GBM 缓冲管理入口 |
| `librea_direct_mesa_gbm_dri.so` | `mesa26` | `framework/build/linux/mesa/lib/gbm/dri_gbm.so` | GBM 的 DRI 后端 |
| `librea_direct_mesa_glx.so` | `mesa26` | `framework/build/linux/mesa/lib/libGLX_mesa.so.0.0.0` | 匹配 Mesa26 的 GLX 模块，满足 SDL 的 GLX 发现/加载需求 |
| `librea_hybris_common.so` | `hybris` | `framework/build/linux/libhybris/install/usr/lib/hybris/libhybris-common.so.1.0.0` | GNU 侧调用 Android/Bionic 库的兼容 hook 和加载桥 |
| `librea_hybris_q.so` | `hybris` | `framework/build/linux/libhybris/install/usr/lib/hybris/libhybris/linker/q.so` | libhybris 内嵌的 Q 系列 Bionic 动态链接器 |
| `librea_direct_hardware.so` | `hybris` | `framework/build/linux/libhybris/install/usr/lib/hybris/libhardware.so.2.0.0` | GNU 侧 Android HAL 模块加载桥 |
| `librea_direct_gralloc.so` | `hybris` | `framework/build/linux/libhybris/install/usr/lib/hybris/libgralloc.so.1.0.0` | GNU 侧图形缓冲分配/导入桥 |
| `librea_direct_ui.so` | `hybris` | `framework/build/linux/libhybris/install/usr/lib/hybris/libui.so.1.0.0` | GNU 侧 Android 图形缓冲接口桥 |
| `librea_vulkan_loader.so` | `vulkan-icd-loader-glibc` | `data/data/com.termux/files/usr/glibc/lib/libvulkan.so.1.3.301` | GNU Vulkan Loader 1.3.301，读取 ICD 描述并派发 Vulkan 调用 |

### 4.3 X11 与配套 GNU 依赖

| APK 内库名 | 来源 ID / 取得方式 | 原包成员或源码位置 | 用途 |
| --- | --- | --- | --- |
| `libXlorie.so` | `termux-x11-apk` | `lib/arm64-v8a/libXlorie.so` | Android/Bionic 侧嵌入式 X11 服务；窗口协议与输入 |
| `librea_egl_4c65c1ff98.so` | `libwayland-glibc` | `data/data/com.termux/files/usr/glibc/lib/libwayland-client.so.0.23.1` | 保留的 GNU 图形依赖；随库存在不表示应用使用 Wayland 呈现 |
| `librea_egl_eb05ef4a77.so` | `libglvnd-glibc` | `data/data/com.termux/files/usr/glibc/lib/libEGL.so.1.1.0` | libglvnd EGL 前端，选择 Mesa EGL 厂商实现 |
| `librea_gl_07d6e1e661.so` | `gcc-libs-glibc` | `data/data/com.termux/files/usr/glibc/lib/libgcc_s.so.1` | GCC 运行时与异常展开支持 |
| `librea_gl_0acf5ebfd0.so` | `libglvnd-glibc` | `data/data/com.termux/files/usr/glibc/lib/libGLdispatch.so.0` | OpenGL 函数派发表 |
| `librea_gl_16263672d4.so` | `zlib-glibc` | `data/data/com.termux/files/usr/glibc/lib/libz.so.1` | zlib 压缩支持 |
| `librea_gl_162daaa727.so` | `libxcb-glibc` | `data/data/com.termux/files/usr/glibc/lib/libxcb-dri3.so.0` | XCB dri3 扩展客户端接口 |
| `librea_gl_29b7a1b389.so` | `libdrm-glibc` | `data/data/com.termux/files/usr/glibc/lib/libdrm.so.2` | Mesa/图形栈所需的 DRM 用户态接口 |
| `librea_gl_4aad5a3b17.so` | `gcc-libs-glibc` | `data/data/com.termux/files/usr/glibc/lib/libstdc++.so.6` | GNU C++ 标准运行库 |
| `librea_gl_5494eea9c1.so` | `libxcb-glibc` | `data/data/com.termux/files/usr/glibc/lib/libxcb-present.so.0` | XCB present 扩展客户端接口 |
| `librea_gl_551cc1b271.so` | `libxxf86vm-glibc` | `data/data/com.termux/files/usr/glibc/lib/libXxf86vm.so.1` | XFree86 视频模式扩展兼容接口 |
| `librea_gl_5a8cfeda9f.so` | `libglvnd-glibc` | `data/data/com.termux/files/usr/glibc/lib/libGL.so.1` | libglvnd 的桌面 OpenGL 前端 |
| `librea_gl_5c267abe4d.so` | `libxcb-glibc` | `data/data/com.termux/files/usr/glibc/lib/libxcb-xfixes.so.0` | XCB xfixes 扩展客户端接口 |
| `librea_gl_63a8695dc3.so` | `libffi-glibc` | `data/data/com.termux/files/usr/glibc/lib/libffi.so.8` | 动态调用/外部函数接口 |
| `librea_gl_65ff900111.so` | `libxcb-glibc` | `data/data/com.termux/files/usr/glibc/lib/libxcb-shm.so.0` | XCB shm 扩展客户端接口 |
| `librea_gl_76b9555ce2.so` | `libexpat-glibc` | `data/data/com.termux/files/usr/glibc/lib/libexpat.so.1` | XML 配置解析 |
| `librea_gl_afa6811ef2.so` | `libxshmfence-glibc` | `data/data/com.termux/files/usr/glibc/lib/libxshmfence.so.1` | X11 共享内存 fence 接口 |
| `librea_gl_b92f3f7ad2.so` | `libx11-glibc` | `data/data/com.termux/files/usr/glibc/lib/libX11-xcb.so.1` | Xlib 与 XCB 连接共享 |
| `librea_gl_c6b18cfa37.so` | `libxcb-glibc` | `data/data/com.termux/files/usr/glibc/lib/libxcb-randr.so.0` | XCB randr 扩展客户端接口 |
| `librea_gl_c9421090be.so` | `libxcb-glibc` | `data/data/com.termux/files/usr/glibc/lib/libxcb-glx.so.0` | XCB glx 扩展客户端接口 |
| `librea_gl_e279577654.so` | `libglvnd-glibc` | `data/data/com.termux/files/usr/glibc/lib/libGLX.so.0.0.0` | libglvnd 的 GLX 前端/厂商派发 |
| `librea_gl_e37a1c7ced.so` | `libxcb-glibc` | `data/data/com.termux/files/usr/glibc/lib/libxcb-sync.so.1` | XCB sync 扩展客户端接口 |
| `librea_x11.so` | `libx11-glibc` | `data/data/com.termux/files/usr/glibc/lib/libX11.so.6.4.0` | Xlib 客户端接口 |
| `librea_xau.so` | `libxau-glibc` | `data/data/com.termux/files/usr/glibc/lib/libXau.so.6.0.0` | X11 认证辅助接口 |
| `librea_xcb.so` | `libxcb-glibc` | `data/data/com.termux/files/usr/glibc/lib/libxcb.so.1.1.0` | XCB 客户端协议接口 |
| `librea_xcursor.so` | `libxcursor-glibc` | `data/data/com.termux/files/usr/glibc/lib/libXcursor.so.1.0.2` | X11 光标支持 |
| `librea_xdmcp.so` | `libxdmcp-glibc` | `data/data/com.termux/files/usr/glibc/lib/libXdmcp.so.6.0.0` | XDMCP 辅助依赖 |
| `librea_xext.so` | `libxext-glibc` | `data/data/com.termux/files/usr/glibc/lib/libXext.so.6.4.0` | X11 扩展客户端接口 |
| `librea_xfixes.so` | `libxfixes-glibc` | `data/data/com.termux/files/usr/glibc/lib/libXfixes.so.3.1.0` | XFixes 扩展客户端接口 |
| `librea_xi.so` | `libxi-glibc` | `data/data/com.termux/files/usr/glibc/lib/libXi.so.6.1.0` | XInput 扩展客户端接口 |
| `librea_xrandr.so` | `libxrandr-glibc` | `data/data/com.termux/files/usr/glibc/lib/libXrandr.so.2.2.0` | XRandR 尺寸/显示扩展接口 |
| `librea_xrender.so` | `libxrender-glibc` | `data/data/com.termux/files/usr/glibc/lib/libXrender.so.1.3.0` | XRender 扩展客户端接口 |

### 4.4 本项目编译的四个库

| APK 内库名 | 来源 ID / 取得方式 | 原包成员或源码位置 | 用途 |
| --- | --- | --- | --- |
| `librea_direct_icd.so` | `本项目编译` | `native-input/direct-wsi/（公开 ICD 核心 + 自有 native-window-surface.c）` | 公开 Vulkan ICD + 自有 AHB 窗口提供者，连接系统 Vulkan 与私有呈现通道 |
| `librea_direct_present.so` | `本项目编译` | `native-input/direct-present-server.c` | Android/Bionic 侧接收 AHardwareBuffer，经 GLES 绘制到 Android Surface |
| `librea_gamepad_bridge.so` | `本项目编译` | `native-input/gamepad-bridge.c + sdl-interface-offsets.h` | 独立 GNU 引擎进程内的 SDL 虚拟手柄/输入转发 |
| `librea_gnu_tls.so` | `本项目编译` | `研究仓库 research/graphics-window/direct-zink/hybris-tls/（上游 TLS 代码经项目适配）` | GNU 启动时预加载 TLS 适配层，为 Bionic GPU 库预留 16 KiB 静态 TLS |

“用途”描述组件在此兼容栈中的职责；清单里包含某个 X11/Wayland 扩展依赖，不代表当前游戏实际调用了该扩展的每个接口。

## 5. 本项目编译组件：源码、工具与可复现边界

日常 APK 构建只恢复和打包固定二进制，Gradle 未配置从全部原生源码重建。当前 [build.gradle.kts](../app/build.gradle.kts) 为这些库保留原始字节，不执行 strip。下面的原生重建是单独的开发流程，会生成新产物；编译器路径、调试信息等也可能影响哈希。未经比较，不宣称新产物与固定库字节一致。

### 5.1 共用 Windows 编译器

使用工程内 Zig C 交叉编译器，不修改系统 PATH：

- [Zig 0.15.2 Windows x86_64 ZIP](https://ziglang.org/download/0.15.2/zig-x86_64-windows-0.15.2.zip)，92614574 字节。
- ZIP SHA-256：`3a0ed1e8799a2f8ce2a6e6290a9ff22e6906f8227865911fb7ddedc3cc14cb0c`。
- 解到 `tools/zig-x86_64-windows-0.15.2/`；`zig.exe` SHA-256：`d408dd38eed3e5204af841bcebf70502a4dbbb8399a3a3262be55059370bc018`。
- GNU 编译目标为 `aarch64-linux-gnu.2.38`；Android 呈现接收器目标为 `aarch64-linux-android.26`，使用 `generic+lse`。

### 5.2 librea_direct_icd.so

源码在 [native-input/direct-wsi](../native-input/direct-wsi)。[sources.json](../native-input/direct-wsi/sources.json) 记录上游文件 SHA-256、修改说明、头文件来源及最终编译输入。

- 上游核心来自 [libhybris 2375acd… 的 hybris/vulkan/icd](https://github.com/taowen/libhybris/tree/2375acd0b5d278a0a2f745c645680545e8f80539/hybris/vulkan/icd)，选取 `icd.c`、`instance.c`、`device.c`、`swapchain.c`、`wsi.c` 及对应头文件。
- 项目修改：`wsi.c` 取消广告 Wayland 扩展；`native-window-surface.c` 用私有 AHardwareBuffer socket 提供窗口传输，保留 release fence 和接收完成确认。
- Vulkan 头文件来自 [Vulkan-Headers 固定提交 952f776…](https://github.com/KhronosGroup/Vulkan-Headers/tree/952f776f6573aafbb62ea717d871cd1d6816c387/include)。AOSP / 自有最小 ABI 声明随源码保存；原 AOSP 部分来源使用过浮动 `main` 地址，应以 `sources.json` 的文件 SHA 与已保存文件为准。
- Windows 重建入口：[scripts/build-direct-wsi.ps1](../scripts/build-direct-wsi.ps1)。先恢复 52 库以提供链接输入，再准备脚本所需工具/头文件目录，然后执行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-direct-wsi.ps1`。
- 输出 `artifacts/direct-wsi/librea_direct_icd.so`，脚本也会覆盖本地 staging 中的同名库。自编改变后，原固定清单校验失败是预期现象，不能直接忽略。

### 5.3 librea_direct_present.so

源码：[native-input/direct-present-server.c](../native-input/direct-present-server.c)。接收 GPU AHardwareBuffer，创建 EGLImage，经一次 GLES 采样绘入 Android Surface。它保留正确性所需的同步；没有整帧 CPU 像素搬运，不代表无需同步或绝对零拷贝。

Windows 入口：[scripts/build-direct-present.ps1](../scripts/build-direct-present.ps1)，执行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-direct-present.ps1`。输出 `artifacts/direct-present/librea_direct_present.so` 并覆盖 staging。

上述两个脚本目前依赖已有 Windows 工程里的目录，干净克隆仅恢复运行库后还不能直接重建：

| 额外输入 | 来源/准备方式 | 限制 |
| --- | --- | --- |
| `tools/venus-host-build/baseline-ef25ad8b/sysroot/usr/include/` | 原始头文件来自 [ndk-sysroot_30_aarch64.deb](https://packages.termux.dev/apt/termux-main/pool/main/n/ndk-sysroot/ndk-sysroot_30_aarch64.deb)，1996888 字节，SHA-256 `73502f9b04fce210c430c83bf9ae2b8e332ba5c319f3eb94a61ba5cef090f787`。取内层 `data.tar.*` 的 `data/data/com.termux/files/usr/include/` 内容，保留 `aarch64-linux-android/` 等层级 | 这是有限头文件输入，不是要求安装整套 Mac NDK；需对照脚本布局放置 |
| `tools/direct-zink-probe/include/` | 历史 probe 头文件缓存在研究仓库 `research/graphics-window/direct-zink/include/`；Khronos/AOSP 来源另见研究清单和 `direct-wsi/sources.json` | 当前独立安卓仓库没有一键恢复这个历史目录的入口 |
| `tools/bionic-link-inputs/libc.so`、`libdl.so` 与 `manifest.json` | 开发阶段从用户测试设备只读取得 `/apex/com.android.runtime/lib64/bionic/` 中的公共链接输入；本项目运行库 ZIP 不含它们 | 仅用于链接，未打包到 APK；新设备输入或标准 NDK 链接 stub 尚未验证为相同构建替代品 |

当时的设备链接输入 SHA-256：`libc.so` 为 `1f52753dd96919f10bca231d828c9b2fd6b49d18fd0fd809b8422a8a4bb8960a`，1348816 字节；`libdl.so` 为 `0481ad1349734ab79535f84d416a022b48403aa0088aaa3dfb584bc7cd783b63`，50832 字节。构建脚本校验它们与本地 manifest 一致。系统升级后文件可能变化，不能凭相同路径宣称字节可复现；从零准备这部分编译输入尚需补充自动化。

### 5.4 librea_gamepad_bridge.so

自有源码：[gamepad-bridge.c](../native-input/gamepad-bridge.c) 和 [sdl-interface-offsets.h](../native-input/sdl-interface-offsets.h)。桥接目标针对当前验证的 Linux ARM64 Factorio 2.1.22 接口，不应假定任意版本兼容。

历史脚本位于研究仓库 `scripts/windows/build-native-gamepad.ps1`，独立安卓仓库尚无同名构建入口。核心编译命令如下；在 Windows 安卓工程根目录、已准备 Zig 后可手工执行（本次未执行重建）：

```powershell
New-Item -ItemType Directory -Force .\artifacts\native-input | Out-Null
& .\tools\zig-x86_64-windows-0.15.2\zig.exe cc -target aarch64-linux-gnu.2.38 -std=c11 -O2 -fPIC -shared -pthread -Wall -Wextra -Werror -fvisibility=hidden -Wl,-z,relro -Wl,-z,now -Wl,--build-id=sha1 .\native-input\gamepad-bridge.c -o .\artifacts\native-input\librea_gamepad_bridge.so
```

历史原生编译脚本不等同于设备测试脚本；只要重建就不应顺带运行安装/探针流程。

### 5.5 librea_gnu_tls.so

它基于 libhybris `hybris/common/bionic_tls.c` / `bionic_tls.h` / `logging.h` 适配，不是完全由本项目原创。原始来源为 [2375acd… 的 common 目录](https://github.com/taowen/libhybris/tree/2375acd0b5d278a0a2f745c645680545e8f80539/hybris/common)，保留 Apache-2.0 版权声明。

本项目修改后的实际输入在研究仓库 `research/graphics-window/direct-zink/hybris-tls/`，包含 `bionic_tls.c`、`bionic_tls.h`、`logging.h`、`config.h`。主要改动包括启动时预留 16384 字节 TLS、边界检查和相应诊断。引擎经 GNU loader 的 `--preload` 使用它；原 common 预编译库保持原样。

历史编译命令来自研究脚本 `scripts/windows/probe-direct-zink-hybris.ps1`，核心为：

```powershell
# 将研究仓库的上述四个文件完整放入 tools/direct-zink-probe/tls/。
& .\tools\zig-x86_64-windows-0.15.2\zig.exe cc -target aarch64-linux-gnu.2.38 -std=c11 -O2 -Wall -Wextra -Werror -D_GNU_SOURCE -fPIC -shared -Wl,-z,nodelete .\tools\direct-zink-probe\tls\bionic_tls.c -pthread -o .\artifacts\librea_gnu_tls.so
```

当前独立安卓仓库没有携带这四个完整适配源码，也没有单独的 TLS 重建入口。只拿上游同名文件编译不能认为等价。**不要为重建而运行整个历史 probe 脚本：其后半段会操作设备。** 当前同字节复现请用第 2 节固定 ZIP；源码重建完整性是已记录的未完成项。

## 6. Compose 新增的 AndroidX 库

`libandroidx.graphics.path.so` 来自 Maven 坐标 `androidx.graphics:graphics-path:1.0.1`（Apache-2.0）。Gradle 的 `google()` 仓库解析 AAR，Android 打包过程自动取其 ARM64 原生库，不由 `RestoreRuntime.java` 恢复。

- [Google Maven AAR](https://dl.google.com/dl/android/maven2/androidx/graphics/graphics-path/1.0.1/graphics-path-1.0.1.aar)
- [对应 POM](https://dl.google.com/dl/android/maven2/androidx/graphics/graphics-path/1.0.1/graphics-path-1.0.1.pom)
- 本地实际 AAR SHA-256：`8ca4032b6d79b351f0b59ad4b580eddbb9423e1652f7c958830687f1eee2ec03`。
- AAR 内位置：`jni/arm64-v8a/libandroidx.graphics.path.so`。
- 单库 10096 字节，SHA-256 `41e9a793c43a0f4fddb19e33f346bace464f30f888ba7b9eaf96294ea115bfb6`。
- APK 位置：`lib/arm64-v8a/libandroidx.graphics.path.so`，用途为 AndroidX/Compose UI 路径处理，不承担游戏 OpenGL/Vulkan 转换。

正常复现用工程固定 Gradle 依赖构建，或手动下载 AAR 校验上述摘要，再当 ZIP 读取指定成员。独立 SHA 清单：[compose-native-files.tsv](../scripts/compose-native-files.tsv)。本次没有重新下载 AAR；来源依据是先前 Windows 本地构建缓存与 APK 的实际比对记录。

## 7. XKB 数据与系统 GPU 驱动的区别

`window-xkb.zip` 不是 `.so`，但它和运行库一起恢复。原始输入为第 3.1 节 `xkeyboard-config-glibc_2.43_all.deb`，从 `share/X11/xkb/` 及 `share/xkeyboard-config-2/` 提取键盘布局/规则文件，形成 295 个文件、3610768 展开字节。历史提取将 `rules/xorg`、`xorg.lst`、`xorg.xml` 的包内符号链接物化为对应 `base*` 内容；ZIP 固定条目时间 1980-01-01，DEFLATE。最终 ZIP 为 723506 字节，SHA-256 `84c41ea4e4b4e029615251c41a78059550fba6e0de8b06d40827183caf36bc2e`。从原数据重新压缩时压缩器版本可改变 ZIP 字节，精确恢复以固定附件为准。

设备的 `/system/lib64/libvulkan.so` 及 `/vendor/lib64/` GPU 驱动由 Android 系统提供，运行时访问，不在本项目下载或打包清单中。不要把本项目的 GNU Vulkan Loader、libhybris 的 `libhardware/libui` 桥误认为设备原厂 GPU 驱动。

## 8. 复现验收与已知缺口

1. 只要目标是构建当前 APK，执行第 2 节恢复及 `--check` 即可，之后按 README 使用 Windows 本地构建入口；不需连设备或重新配对。
2. 要核对上游出处，先验证下载包摘要，再验证指定成员与附录 A 的摘要相同。相同文件名、库版本文字或浮动 Release 标签均不足以证明内容相同。
3. 要重建原生库，必须准备第 5 节所列源码、编译器、头文件与链接输入。现有工程有历史构建成功记录，但干净克隆的全部原生源码重建流程尚不完整；这与“52 个二进制可恢复并构建 APK”分开验收。
4. Mesa/libhybris/glibc 预编译包与所检查 Git commit 的准确构建对应关系未全部确认；Termux:X11 的 commit 关系来自上游发布说明，未做源码重建一致性验证。
5. 本次检查确认 52 个 staging 库和 XKB、固定 ZIP 的全部 53 个运行项都与锁定清单一致；48 个上游预编译库均从缓存的原始下载包中重新读取成员，与当前库逐个核对 SHA-256 一致。覆盖情况见配套研究核对记录。未重新联网证明旧 URL 当前仍可用，未触发云构建、本地 APK 构建或任何设备操作。

许可证和版权文字见 [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md) 与 `app/src/main/assets/*COPYRIGHT.txt`。部分许可证汇总包含历史组件，不表示其库仍在当前包中。

## 附录 A：当前 52 个库的大小与 SHA-256

此表逐项复制自 [runtime-files.tsv](../scripts/runtime-files.tsv)，字节数是库未压缩大小，不是 APK 内压缩后大小。

| 库名 | 字节 | SHA-256 |
| --- | --- | --- |
| `libXlorie.so` | 3552072 | `8ad76408710c21b9a61d3db07900e5bb4e3f88efb1670ac0ff3a2166f403567d` |
| `librea_direct_gralloc.so` | 114632 | `1bfbeed2b9ac05c2c3688cba168d1e96880e3fa5426a1a177300ddf82a8205c7` |
| `librea_direct_hardware.so` | 74120 | `09b42df2779710c02e0ffec4894bd315809319448acb925a13e3f8759b310244` |
| `librea_direct_icd.so` | 359992 | `d1171a4d85c96a2ebd35dc0dd0ab66f9e11cc578b4c604898409f682a2c8fa0f` |
| `librea_direct_mesa_dril.so` | 144416 | `10b5a54647c91007889ef231a5c002accb05ee24a3530abcafe94785f18d036e` |
| `librea_direct_mesa_egl.so` | 475568 | `c172a3a59bc76026bcf4b481062bb33a6fd63543a90db7220d720935a8a84e80` |
| `librea_direct_mesa_gallium.so` | 22907536 | `7191f9b66182935c557a96a8b3895be1a15fad7a9b17f1947d9e313de41ce88f` |
| `librea_direct_mesa_gbm.so` | 75640 | `ad37b04c81a031f723fbb73de00225c417ba4fc653c14c287c4ed546de95f13c` |
| `librea_direct_mesa_gbm_dri.so` | 150240 | `d909c027a6127713dcc2a195b1192a4a4f5804b6db34fd44dba31f704c9f98f1` |
| `librea_direct_mesa_glx.so` | 495536 | `b1b01cdb8cedeaf45d16e7dd3938c0500ebdf9f7e9fa8e2d0393797e8aef4b65` |
| `librea_direct_present.so` | 55968 | `42f7ba79cde7eeea5c7eeb939c27afcdf13d5fa28c2530be40687b411b35d00a` |
| `librea_direct_ui.so` | 85264 | `8f5c96ea6671661d8825602b852b02698f3aeafa2477596be42339d2857b8408` |
| `librea_egl_4c65c1ff98.so` | 133856 | `8a6f8fad77f495d7a0947ae613d93100968e1e088c07d0b35d87201f692fc626` |
| `librea_egl_eb05ef4a77.so` | 133064 | `c68dd781b527acde96d3e23229bfca024826639f7d07705f25a2198b1480fda1` |
| `librea_gamepad_bridge.so` | 28968 | `c5f7624ccdaf635dd487a51c1c3093ddb2e4e3a257c8545d1477c4f387ba56c1` |
| `librea_gl_07d6e1e661.so` | 729184 | `22d5db25fece632754f7a0fb1c6586eb6e93eab6a5866da487b86f115d4e389d` |
| `librea_gl_0acf5ebfd0.so` | 1574952 | `892feaa3f76bccd236db5dde79b175a74212296f12eed003eb80b432cf6fb949` |
| `librea_gl_16263672d4.so` | 144368 | `fd9dd63116647e60e57ac1bd874625858bf0b0d05fab015e0c2db1c9c04f3a99` |
| `librea_gl_162daaa727.so` | 73992 | `8039415811aa6c775e59457436b02cd302d7973d9fbd69468fcc4177882614df` |
| `librea_gl_29b7a1b389.so` | 133240 | `50f3234f9e375d26e77539165370c233feb3c18612365f6c3ca2b06cc9661970` |
| `librea_gl_4aad5a3b17.so` | 22487888 | `4205855cce5501831acbcb88b024f705ec0f355ca8ea0ad62228848081e9520d` |
| `librea_gl_5494eea9c1.so` | 71992 | `1969de5b3ffa90af342e15748c10eed14bd9c40d901ce4ade0275fd16457a981` |
| `librea_gl_551cc1b271.so` | 71640 | `86d25b68b25deda79d5b9b6d070665170ee522bf905b63a0b88edd469003e969` |
| `librea_gl_5a8cfeda9f.so` | 985048 | `4e2135680cdd006b7b5ca37e6930eafcdc56ad15357769841d2394fac23b9a83` |
| `librea_gl_5c267abe4d.so` | 79456 | `db5ce18da1b567a27ecd1a9e0550661dd196318448203bcf29a816d1193e9130` |
| `librea_gl_63a8695dc3.so` | 82352 | `8a8cfcbe243dbca0e642c6bd427241f8ff6b26b4a0d89b130679aa7b1c846abd` |
| `librea_gl_65ff900111.so` | 71616 | `e4e455da0829126d0fc2a4c2554bce4d9b122bc26ff2b43ecb0bdec07c8d2c30` |
| `librea_gl_76b9555ce2.so` | 219712 | `7b563b60fc16499a45b471c0006c105c1e706cc5215f6ec499b317d9a2c8dd8e` |
| `librea_gl_afa6811ef2.so` | 73312 | `5a0652d801ce63a2e33ccb1ba035a91f3212a89570083239ff6ee66e2effb9cb` |
| `librea_gl_b92f3f7ad2.so` | 69528 | `7ab88f66a4c53d8841cbfa3afa29e50eeff70678434c58c7171961b6da78cca5` |
| `librea_gl_c6b18cfa37.so` | 92392 | `ef12d668682ac99dba386b017fc24609dfa81ad948accf5b3894d91850bf0679` |
| `librea_gl_c9421090be.so` | 174760 | `793d94c410bad7152a202e3d38831b63f18b96bd64687d030ac55f7099f4952d` |
| `librea_gl_e279577654.so` | 198544 | `1e8e5721049f2ae5fc076f25b908b92ad57277380f1b29c3543f5f53cb9d2328` |
| `librea_gl_e37a1c7ced.so` | 76312 | `2dc864a8050acf3ab0eb896dae57a7991fdc4a76f7d583568b931137a0749e22` |
| `librea_glibc.so` | 2368240 | `cb7b001c0d4030119eb9010ae2d747fcbc51108cfb1e0940fa78618d78dc9ae9` |
| `librea_glibm.so` | 1209792 | `6e070086457ddc46e8fbbd2ed11a63bb0a394e9eeb41bbfbe1825aa286f9cbb1` |
| `librea_gnu_tls.so` | 17424 | `fcfa4236c7175818a5cf272b6f81480b0f79ce94ea6c6330310012728e7da4e6` |
| `librea_hybris_common.so` | 634288 | `b2dc714be744024728f900a42b321f8129b0000d90c5e04ad64123002ffaa7d6` |
| `librea_hybris_q.so` | 5367784 | `65ea85da48354b23738bea3f5a3682b4635a663bfdf362891a2186308f890643` |
| `librea_loader.so` | 242560 | `ca4af7c4022222bb405b0b601d818506ea608a2242f3cce43bd0b56b71f94530` |
| `librea_resolv.so` | 80320 | `490093f15e8f6a5966e89f61c01887657ababeb154b755c48d91a6e2ab9888f2` |
| `librea_vulkan_loader.so` | 695400 | `60079bc90956c55ae10b9bd18ab60f42f92bcf0c8e22768b7ae3be7518f4549f` |
| `librea_x11.so` | 1536264 | `1d28ce6412b7919e6ad7592d327e7ee5fdf72551868657b8d8eecc3f8ac69e04` |
| `librea_xau.so` | 72048 | `1a8bc733979360f2e3327d805efe64f9306247bacb90cac9efdc0cf1e033bb89` |
| `librea_xcb.so` | 249712 | `b57cfc342b0efd864caa86773b33e4af882c3863e99441468a76849cedf0b2e2` |
| `librea_xcursor.so` | 77288 | `86e70b94186edb4c16cf00f2bcea4a03ea09bb486ee6a079dd11d3ef6fffe722` |
| `librea_xdmcp.so` | 72536 | `0245743bb594ee1ac2c1325873d70407c790f890477d8a0fe8323bda8803f705` |
| `librea_xext.so` | 150064 | `a93ba9939545c2ea11ebba52a720abb1e35223b2f3ee2a8a86ab93426ad5845c` |
| `librea_xfixes.so` | 72968 | `271e82cbc4aa3db8ff36ad44735552153b6fbaa787bf59b6ec0b20f63d0f386d` |
| `librea_xi.so` | 146048 | `6f50cad56bb44c7900e692697bb5afcdd68c889be0c95eeda3775c8a2ac4de00` |
| `librea_xrandr.so` | 75224 | `c1b8c73864685b43eeead0ae3b3b49207b9b1db537326cb536a53479fd6a808b` |
| `librea_xrender.so` | 74928 | `d05d09c2acfd577d27b7cc02f59497841a8c38fec5634cfb8eab0eaa76cc2dce` |

## 附录 B：本说明使用的研究证据

以下路径属于研究仓库 `rea/`，不要求独立安卓仓库存在这些目录。下载地址、原包成员和哈希已完整写进本文，独立源码读者不需访问研究目录即可按第 2–4 节恢复/核对预编译文件。

- `research/runtime-probe/termux-glibc/input.json`：glibc 包、提取成员、未做签名/源码提交一致性验证的记录。
- `research/graphics-window/selected-packages.json`、`selected-gl-packages.json`、`selected-venus-packages.json`：精确 DEB URL、版本、大小和 SHA。
- `research/graphics-window/candidate-inputs.json`、`gl-candidate-inputs.json`、`egl-candidate-inputs.json`、`venus-candidate-inputs.json`：包内成员与单文件 SHA；文档只取当前 52 库中仍使用的项。
- `research/graphics-window/direct-zink/mesa-release.json`、`hybris-release.json`、`mesa-source.json`、`hybris-source.json`：Release 附件和源码检查范围。
- `research/graphics-window/termux-x11/release-record.json`、`source-manifest.json`：APK 来源及上游声明的源码基线。
- `research/graphics-window/host-build/selected-build-deps.json`、`bionic-link-inputs.json`：有限编译头文件与设备链接输入。
- `research/launcher-ui/runtime-payload-package.json`、`RUNTIME-RESTORE.md`：项目固定 ZIP 和既有恢复验证。
- `research/launcher-ui/compose/androidx-graphics-native-source.json`：实际 AAR 与库摘要。
- `research/launcher-ui/native-library-documentation.json`：本次只读文档核对结果；不含二进制或账号凭据。

