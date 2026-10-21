# jniLibs 原生库来源、用途与获取方式

本文说明启动器附带的原生库分别来自哪里、承担什么功能，以及如何重新获取。下载版本和包内路径均列在下文；构建工具会自动完成文件校验，本文不重复罗列校验值。

## 1. 这些文件的作用

`app/src/main/jniLibs/arm64-v8a/` 中的 52 个 `.so` 组成游戏所需的原生运行环境。其中 48 个取自开源组件的预编译包，4 个由本项目编译或适配后编译。它们提供 Linux 基础运行库、图形转换、窗口、输入和 Android 呈现功能。

启动器的 Kotlin/Java 代码负责界面、导入、设置和会话管理；真正的 Factorio 引擎与游戏数据来自用户自行导入的官方 Linux ARM64 安装包。

放在 `jniLibs` 中不表示这些库都通过 JNI 加载到 Java 进程。glibc、Mesa 等运行在独立的游戏进程中；`libXlorie.so` 和画面接收器运行在 Android 侧。打包时有些库改用了私有名称，例如 `librea_glibc.so` 对应原始的 `libc.so.6`；启动时会建立游戏需要的文件名别名。文件名前缀不代表该组件由本项目原创。

Compose 还会通过 AndroidX 依赖额外带入 `libandroidx.graphics.path.so`，其获取方式见第 6 节。安卓软键盘接入还会从启动器源码编译 `librea_ime_opener.so`，因此当前 APK 共包含 54 个原生文件。该程序的用途和完整获取步骤见 [安卓软键盘接入](ANDROID-KEYBOARD.md)。

```text
Linux ARM64 Factorio
  → GNU 加载器与 glibc
  → OpenGL / Mesa Zink
  → Vulkan 加载器与 Android 图形兼容层
  → 设备自带的 GPU 驱动
  → AHardwareBuffer → Android Surface

X11 负责窗口协议和输入，当前画面呈现不通过它传输整帧像素。
```

## 2. 直接恢复启动器所需的运行库

最方便的获取方式是使用运行库恢复工具。在工程根目录执行，要求 JDK 11 或更高版本：

```powershell
java scripts/RestoreRuntime.java
java scripts/RestoreRuntime.java --check
```

构建入口也会自动调用恢复工具。下载内容包括 52 个 `.so` 和键盘布局数据 `window-xkb.zip`，不含游戏引擎、游戏资源或设备 GPU 驱动。

也可以[下载运行库 ZIP](https://github.com/PlutoCRown/FactorioAndroid/releases/download/runtime-20261021-v1/factorio-android-runtime-arm64-20261021-v1.zip)，然后离线恢复：

```powershell
java scripts/RestoreRuntime.java --archive C:\Downloads\factorio-android-runtime-arm64-20261021-v1.zip
java scripts/RestoreRuntime.java --check
```

下载需要身份验证时，可以登录后手动取得 ZIP；自动下载支持读取环境变量 `GITHUB_TOKEN` 或 `GH_TOKEN`。需要的权限是 Contents: read，不要把访问令牌写进源码。

恢复工具会验证压缩包及每个文件，已存在但内容不同的文件默认不会覆盖。确需恢复到指定版本时添加 `--force`。网络不佳时可以先用支持续传的下载工具取得完整 ZIP，再离线恢复。

下载配置见 [runtime-payload.properties](../scripts/runtime-payload.properties)，完整校验清单见 [runtime-files.tsv](../scripts/runtime-files.tsv)，工具实现见 [RestoreRuntime.java](../scripts/RestoreRuntime.java)。

## 3. 从上游分别获取预编译组件

如果需要了解每个库的原始出处，或单独重新提取，可以按本节下载对应版本，再按第 4 节的包内路径取出文件。

### 3.1 Termux DEB 软件包

这些包提供 GNU 基础库、X11 客户端库和图形配套依赖。只需下载并解包，不需要安装 Termux，也不需要运行包内安装脚本。

| 软件包 | 版本 | 下载地址 |
| --- | --- | --- |
| `gcc-libs-glibc` | 14.2.1-1 | [gcc-libs-glibc_14.2.1-1_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/g/gcc-libs-glibc/gcc-libs-glibc_14.2.1-1_aarch64.deb) |
| `glibc` | 2.44 | [glibc_2.44_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/g/glibc/glibc_2.44_aarch64.deb) |
| `libdrm-glibc` | 2.4.134 | [libdrm-glibc_2.4.134_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libd/libdrm-glibc/libdrm-glibc_2.4.134_aarch64.deb) |
| `libexpat-glibc` | 2.8.4 | [libexpat-glibc_2.8.4_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libe/libexpat-glibc/libexpat-glibc_2.8.4_aarch64.deb) |
| `libffi-glibc` | 3.8.0 | [libffi-glibc_3.8.0_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libf/libffi-glibc/libffi-glibc_3.8.0_aarch64.deb) |
| `libglvnd-glibc` | 1.7.0-1 | [libglvnd-glibc_1.7.0-1_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libg/libglvnd-glibc/libglvnd-glibc_1.7.0-1_aarch64.deb) |
| `libwayland-glibc` | 1.23.1 | [libwayland-glibc_1.23.1_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libw/libwayland-glibc/libwayland-glibc_1.23.1_aarch64.deb) |
| `libx11-glibc` | 1.8.11 | [libx11-glibc_1.8.11_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libx11-glibc/libx11-glibc_1.8.11_aarch64.deb) |
| `libxau-glibc` | 1.0.12 | [libxau-glibc_1.0.12_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxau-glibc/libxau-glibc_1.0.12_aarch64.deb) |
| `libxcb-glibc` | 1.17.0-1 | [libxcb-glibc_1.17.0-1_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxcb-glibc/libxcb-glibc_1.17.0-1_aarch64.deb) |
| `libxcursor-glibc` | 1.2.3 | [libxcursor-glibc_1.2.3_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxcursor-glibc/libxcursor-glibc_1.2.3_aarch64.deb) |
| `libxdmcp-glibc` | 1.1.5 | [libxdmcp-glibc_1.1.5_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxdmcp-glibc/libxdmcp-glibc_1.1.5_aarch64.deb) |
| `libxext-glibc` | 1.3.6 | [libxext-glibc_1.3.6_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxext-glibc/libxext-glibc_1.3.6_aarch64.deb) |
| `libxfixes-glibc` | 6.0.1 | [libxfixes-glibc_6.0.1_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxfixes-glibc/libxfixes-glibc_6.0.1_aarch64.deb) |
| `libxi-glibc` | 1.8.2 | [libxi-glibc_1.8.2_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxi-glibc/libxi-glibc_1.8.2_aarch64.deb) |
| `libxrandr-glibc` | 1.5.4 | [libxrandr-glibc_1.5.4_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxrandr-glibc/libxrandr-glibc_1.5.4_aarch64.deb) |
| `libxrender-glibc` | 0.9.12 | [libxrender-glibc_0.9.12_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxrender-glibc/libxrender-glibc_0.9.12_aarch64.deb) |
| `libxshmfence-glibc` | 1.3.3 | [libxshmfence-glibc_1.3.3_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxshmfence-glibc/libxshmfence-glibc_1.3.3_aarch64.deb) |
| `libxxf86vm-glibc` | 1.1.6 | [libxxf86vm-glibc_1.1.6_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/libx/libxxf86vm-glibc/libxxf86vm-glibc_1.1.6_aarch64.deb) |
| `vulkan-icd-loader-glibc` | 1.3.301 | [vulkan-icd-loader-glibc_1.3.301_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/v/vulkan-icd-loader-glibc/vulkan-icd-loader-glibc_1.3.301_aarch64.deb) |
| `xkeyboard-config-glibc` | 2.43 | [xkeyboard-config-glibc_2.43_all.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/x/xkeyboard-config-glibc/xkeyboard-config-glibc_2.43_all.deb) |
| `zlib-glibc` | 1.3.2 | [zlib-glibc_1.3.2_aarch64.deb](https://packages.termux.dev/apt/termux-glibc/pool/stable/z/zlib-glibc/zlib-glibc_1.3.2_aarch64.deb) |

DEB 是 `ar` 容器，实际文件位于里面的 `data.tar.*`。原包中的 `data/data/com.termux/files/usr/glibc/lib/` 是归档路径，提取后按第 4 节重命名放入 `jniLibs`，不需要在设备上创建 Termux 的安装目录。

下面以 `libglvnd-glibc` 中的 `libGL.so.1` 为例。先下载上表对应包，再在工程内临时目录解包；命令要求 `tar.exe` 支持对应压缩格式：

```powershell
New-Item -ItemType Directory -Force .\work\glvnd | Out-Null
# 先查看 DEB 的内层归档名称。
tar.exe -tf .\libglvnd-glibc_1.7.0-1_aarch64.deb
tar.exe -xf .\libglvnd-glibc_1.7.0-1_aarch64.deb -C .\work\glvnd data.tar.xz
# 提取需要的库。
tar.exe -xf .\work\glvnd\data.tar.xz -C .\work\glvnd data/data/com.termux/files/usr/glibc/lib/libGL.so.1
Copy-Item .\work\glvnd\data\data\com.termux\files\usr\glibc\lib\libGL.so.1 .\app\src\main\jniLibs\arm64-v8a\librea_gl_5a8cfeda9f.so
```

不同包可能使用 `data.tar.gz` 或其他压缩格式，应以实际目录列表为准；成员路径若带 `./`，也保留实际前缀。上游旧版本可能被移除，无法取得时使用第 2 节的完整运行库 ZIP。

### 3.2 Mesa 26.3.0-devel

Mesa 提供桌面 OpenGL 实现，其中 Zink 将 OpenGL 调用转换为 Vulkan。当前使用的是 taowen 提供的 Linux ARM64 预编译组件：

- [Mesa 组件下载页](https://github.com/taowen/mesa/releases/tag/arlinux-linux-mesa-debug)
- [直接下载 Mesa 压缩包](https://github.com/taowen/mesa/releases/download/arlinux-linux-mesa-debug/capsule-9c1f55dbd8838304013b86312706e2983e9fc0d0abfe01a803404d0745faf666-000.zst.part)
- [组件说明 component.json](https://github.com/taowen/mesa/releases/download/arlinux-linux-mesa-debug/component.json)

压缩包约 10.2 MB，是 zstd 压缩的 TAR。当前文件只有一段，下载后可重命名为 `mesa-runtime.zst`，使用支持 zstd 的解压工具直接读取，不需要执行安装器。

需要的六个文件位于 `framework/build/linux/mesa/lib/` 下，完整路径见第 4.2 节。提取主库的例子：

```powershell
New-Item -ItemType Directory -Force .\work\mesa | Out-Null
tar.exe -tf .\mesa-runtime.zst
tar.exe -xf .\mesa-runtime.zst -C .\work\mesa framework/build/linux/mesa/lib/libgallium-26.3.0-devel.so
Copy-Item .\work\mesa\framework\build\linux\mesa\lib\libgallium-26.3.0-devel.so .\app\src\main\jniLibs\arm64-v8a\librea_direct_mesa_gallium.so
```

其余文件按第 4.2 节提取和重命名。其中 `libdril_dri.so` 保存为 `librea_direct_mesa_dril.so`，启动时再建立 `dri/zink_dri.so` 别名。

### 3.3 libhybris

libhybris 让 GNU/Linux 进程能够使用 Android/Bionic 的系统接口。当前预编译组件来自：

- [libhybris 组件下载页](https://github.com/taowen/libhybris/releases/tag/arlinux-linux-hybris-fe51c711ae44676f7ef5c73c)
- [直接下载 libhybris 压缩包](https://github.com/taowen/libhybris/releases/download/arlinux-linux-hybris-fe51c711ae44676f7ef5c73c/capsule-000.zst.part)
- [组件说明 component.json](https://github.com/taowen/libhybris/releases/download/arlinux-linux-hybris-fe51c711ae44676f7ef5c73c/component.json)

压缩包约 3.8 MB，同样是单段 zstd TAR。只需取出第 4.2 节列出的五个文件，公共路径为 `framework/build/linux/libhybris/install/usr/lib/hybris/`。

不要把压缩包内全部库一起复制到工程。这里使用的组件只有 common、内嵌链接器及 hardware/gralloc/ui 桥。当前 `librea_vulkan_loader.so` 来自第 3.1 节的 Vulkan Loader 1.3.301，而 `librea_direct_icd.so` 使用本项目编译的适配实现。

### 3.4 Termux:X11

`libXlorie.so` 来自 Termux:X11 的 ARM64 预编译库：

- [Termux:X11 下载页](https://github.com/termux/termux-x11/releases/tag/nightly)
- [直接下载 universal APK](https://github.com/termux/termux-x11/releases/download/nightly/termux-x11-universal-debug.apk)

把 APK 作为 ZIP 解包，取出 `lib/arm64-v8a/libXlorie.so`，保持原文件名放进工程。无需安装该 APK：

```powershell
New-Item -ItemType Directory -Force .\work\xlorie | Out-Null
tar.exe -xf .\termux-x11-universal-debug.apk -C .\work\xlorie lib/arm64-v8a/libXlorie.so
Copy-Item .\work\xlorie\lib\arm64-v8a\libXlorie.so .\app\src\main\jniLibs\arm64-v8a\libXlorie.so
```

这个下载地址会更新。若需要与启动器当前依赖完全一致的文件，优先使用第 2 节运行库 ZIP；不要把新下载的同名库直接视为相同版本。

## 4. 每个库的来源和用途

表中的软件包名称对应第 3.1 节；`mesa26`、`hybris` 和 `termux-x11-apk` 分别对应第 3.2–3.4 节。DEB 路径相对于内部 `data.tar.*`，其他归档路径相对于压缩包根目录。

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
| `librea_gnu_tls.so` | `本项目编译` | 基于 libhybris 的 TLS 代码适配后编译 | GNU 启动时预加载 TLS 适配层，为 Bionic GPU 库预留 16 KiB 静态 TLS |

清单中保留某个 X11 或 Wayland 配套依赖，并不表示当前画面通过该协议传输；它们也可能是其他图形组件的加载依赖。

## 5. 本项目编译或适配的组件

普通 APK 构建会恢复这些库的预编译文件，不会自动从原生源码重新编译。以下说明其代码来源与编译入口；完整源码重建还需要准备相应头文件和链接输入。

### librea_direct_icd.so

来源是 libhybris 的公开 Vulkan ICD 实现，加上本项目的 Android 窗口适配。源码在 [native-input/direct-wsi](../native-input/direct-wsi)，输入来源和修改说明见 [sources.json](../native-input/direct-wsi/sources.json)。

核心修改是用 `native-window-surface.c` 建立 AHardwareBuffer 传输通道，并调整支持的窗口接口。它负责把 GNU 图形栈连接到 Android Vulkan，保留缓冲区同步和接收完成确认。

编译入口为 [build-direct-wsi.ps1](../scripts/build-direct-wsi.ps1)。

### librea_direct_present.so

源码是 [direct-present-server.c](../native-input/direct-present-server.c)，编译入口为 [build-direct-present.ps1](../scripts/build-direct-present.ps1)。它在 Android 侧接收 AHardwareBuffer，通过 EGLImage 和 GLES 将 GPU 图像绘制到 Android Surface。

这条路径保留一次 GPU 采样和必要同步，不需要把整帧像素搬到 CPU 后再传输。

### librea_gamepad_bridge.so

源码是 [gamepad-bridge.c](../native-input/gamepad-bridge.c) 和 [sdl-interface-offsets.h](../native-input/sdl-interface-offsets.h)，用于将手柄输入交给游戏的 SDL 接口。目前适配目标为 Linux ARM64 Factorio 2.1.22。

### librea_gnu_tls.so

基于 libhybris 的 `bionic_tls.c` 等代码适配，保留上游 Apache-2.0 版权声明。它在游戏启动时预加载，为 Android 图形库预留 16 KiB 线程局部存储，并检查访问边界。

这个库目前通过运行库包提供，尚无独立重建入口。直接编译上游同名源码不会自动包含本项目适配。

### 编译工具与复现范围

原生组件使用 [Zig 0.15.2 Windows 版](https://ziglang.org/download/0.15.2/zig-x86_64-windows-0.15.2.zip) 交叉编译。GNU 侧目标为 `aarch64-linux-gnu.2.38`，Android 呈现接收器目标为 `aarch64-linux-android.26`。

头文件包括 Vulkan、Android 和 GNU 侧接口声明；部分 Android 头文件来自 [ndk-sysroot 30 软件包](https://packages.termux.dev/apt/termux-main/pool/main/n/ndk-sysroot/ndk-sysroot_30_aarch64.deb)。两个现有编译脚本还需要相应链接输入，不能仅凭 C 文件直接构建全部库。需要构建启动器时，使用第 2 节预编译恢复即可。

## 6. Compose 的 AndroidX 路径库

`libandroidx.graphics.path.so` 用于 AndroidX/Compose 的 UI 路径处理。它来自 Maven 依赖 `androidx.graphics:graphics-path:1.0.1`，不参与游戏图形转换：

- [下载 graphics-path-1.0.1.aar](https://dl.google.com/dl/android/maven2/androidx/graphics/graphics-path/1.0.1/graphics-path-1.0.1.aar)
- [依赖说明 POM](https://dl.google.com/dl/android/maven2/androidx/graphics/graphics-path/1.0.1/graphics-path-1.0.1.pom)

AAR 也是 ZIP，所需文件位于 `jni/arm64-v8a/libandroidx.graphics.path.so`。Gradle 正常解析依赖时会自动把它放入 APK，不需要手动加入 `jniLibs`，也不由运行库恢复工具下载。

## 7. XKB 数据和设备驱动

`window-xkb.zip` 是键盘布局和规则数据。它来自第 3.1 节的 `xkeyboard-config-glibc_2.43_all.deb`，取包中的 `share/X11/xkb/` 和 `share/xkeyboard-config-2/` 数据，重新打包为启动器使用的 ZIP。

运行库恢复工具会一起取得这份数据，因此一般无需手动制作。手动提取时，需要把 `rules/xorg`、`xorg.lst` 和 `xorg.xml` 指向的 `base*` 内容保留下来。

设备的 `/system/lib64/libvulkan.so` 和 `/vendor/lib64/` 中的 GPU 驱动由 Android 系统提供，运行时直接使用，不由启动器下载或打包。GNU Vulkan Loader 和 libhybris 的图形桥与设备驱动各自承担不同职责。

## 8. 使用这些说明时需要区分的事项

- 要获取启动器当前使用的文件，执行运行库恢复命令；它会自动验证内容。
- 要核对上游来源，按具体软件包版本和归档成员路径提取，再执行 `--check`。下载页面可能更新或移除旧文件。
- 恢复预编译库与从源码重新编译是两种流程。目前可恢复全部运行库，全部组件的源码重建流程仍未完整提供。

各组件的许可证见 [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md) 和 `app/src/main/assets/*COPYRIGHT.txt`。
