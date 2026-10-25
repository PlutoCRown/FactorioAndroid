# 传统 Vulkan 渲染路径：来源、构建与验证

这里记录 Mesa 23.1.9 源码与补丁。它保留传统 render pass 和描述符实现，作为缺少 dynamic rendering 或 nullDescriptor 能力时自动选择的运行库。五个 provider 随 APK 一起交付，能力足够的驱动继续使用原有运行库；选择依据是实际功能查询，不是设备型号。

`inputs.json` 指定官方 Mesa 源码包、公开校验值和额外的 Debian 开发头文件包。通用交叉编译工具、其余头文件和现有 GNU 链接依赖复用 `../mesa-compat/inputs.json` 及 `scripts/build-mesa-runtime.py` 的准备流程。

`build-compat.patch` 包含两项构建与诊断适配：使用 `packaging.version` 代替 Python 3.12 已移除的 `distutils.version`；将必需扩展缺失消息改为 release 构建仍保留的错误日志。它不删除 Vulkan 功能要求，也不改写驱动能力。

`presentation.patch` 修复关闭垂直同步时无条件选择 MAILBOX 的旧假设：仅在呈现模式列表包含该模式时使用它，否则保留 Vulkan 必须提供的 FIFO。它不让 WSI 虚报未实现的呈现模式。

`rgba-window.patch` 将本项目 AHB 呈现窗口的 8 位颜色资源统一为 RGBA/RGBX，避免沿用仅用于输入和尺寸管理的 X11 BGR 视觉格式。作用范围由 `REA_DIRECT_SOCKET` 确定；不按 GPU 型号判断，不改变 pbuffer、pixmap 或其他颜色位宽，不在每帧添加 CPU 映射或读回。

`texel-buffer-limit.patch` 回移较新 Zink 对纹素缓冲上限计算的 64 位乘法修复。原实现先以 32 位计算“每纹素字节数 × 最大纹素数”，再赋给 64 位变量；较大但合法的驱动上限可使结果溢出为零，从而破坏整个缓冲的视图。回归探针比较完整绑定、重复绘制、覆盖整段的 Range、较短 Range 和带偏移 Range，要求同一数据得到同样的颜色。修复保留 `VK_WHOLE_SIZE` 的原有语义，不改写驱动上限。

## 从源码构建

在 macOS/Linux 的项目根目录执行：

```sh
java scripts/RestoreRuntime.java
java scripts/BuildImeNative.java --gnu-only
python3 scripts/build-legacy-mesa.py --jobs 6
```

首次使用时脚本按需通过现有的 `build-mesa-runtime.py --providers-only` 准备工具和依赖，无需 Android SDK/NDK。项目的第三方基础运行库需先按 `docs/SOURCE-BUILD.md` 恢复。旧版导出表列有未启用驱动的符号，构建脚本为 lld 显式使用 `--undefined-version`，保持 GNU ld 对该导出表的兼容行为。

输出 `.cache/mesa-legacy-providers.zip` 包含：

- `dri/zink_dri.so`：由 `src/gallium/targets/dri/libgallium_dri.so` 生成。
- `libEGL_mesa.so.0`、`libGLX_mesa.so.0`：分别来自 `src/egl`、`src/glx`。
- `libgbm.so.1`：来自 `src/gbm`。
- `libglapi.so.0`：来自 `src/mapi/shared-glapi`。
- `source-build.json`：版本、来源和各文件构建记录。
- `legacy-mesa.properties`：构建输入及产物校验信息，供 APK 组装使用。

## 组装进 APK

`BuildMesaNative.java` 调用 `BuildLegacyMesaNative.java`，后者验证源码构建缓存并将上述五个库映射为 `librea_legacy_mesa_{gallium,egl,glx,gbm,glapi}.so`，同时生成 APK 内的 `legacy-mesa.properties`。Gradle 将它们加入原生载荷，`BuildLauncher.java` 检查五个库均存在且与本轮产物一致。

macOS/Linux 缺少当前输入对应的缓存时自动调用源码构建脚本。Windows 先从上述命令生成 GNU provider，再复制 `.cache/mesa-legacy-providers.zip`，或传入其位置：

```powershell
$env:FACTORIO_LEGACY_MESA_ARCHIVE='C:\build\mesa-legacy-providers.zip'
java scripts/BuildMesaNative.java
```

ZIP 是由这些源码构建的缓存，不必从其他人的机器或发布附件获取。Android ICD、接收器和 Vulkan 能力查询仍由当前源码重建；传统 provider 复用同一套自有桥接组件。云端 Linux 构建使用同一流程，无须预先把这些 SO 提交到 Git。

`native-input/graphics-capabilities.c` 查询系统 Vulkan 驱动扩展及特性；`GraphicsCapabilities.java` 处理查询结果，`LegacyMesaRuntime.java` 组成完整的 provider 清单，`DirectZinkRuntime.java` 选择并记录运行库。传统路径使用 `MESA_GL_VERSION_OVERRIDE=3.3` 创建游戏请求的上下文；这项声明已通过相关探针及实际游戏画面验证，但不代表完整 OpenGL 一致性认证。

## 独立设备探针

先安装同签名的 debug 启动器，导入游戏并完成一次启动记录，再结束会话。在 macOS/Linux 执行：

```sh
python3 scripts/check-legacy-zink.py --serial DEVICE_SERIAL
python3 scripts/check-legacy-zink.py --serial DEVICE_SERIAL --gl-version 3.3 --output .cache/legacy-zink-gl33.log
# 经过相同 AHB 接收器呈现左红、中绿、右蓝色带，并与安卓截图对照。
python3 scripts/check-legacy-zink.py --serial DEVICE_SERIAL --gl-version 3.3 --window --output .cache/legacy-zink-window.log
```

脚本将运行库复制到应用私有的 `runtime/legacy-zink-evaluation`，启动已有的调试 X 服务，并从 `tests/device/ZinkBlendProbe.c` 编译 GNU AArch64 探针。它复用实际会话的 GNU loader、ICD 和桥接环境，验证 EGL、OpenGL 上下文、透明混合和纹理采样的读回结果。它不改动游戏、存档和原运行库。

`--gl-version` 只用于判断上下文版本声明之后的实际绘制能力，不能证明完整 OpenGL 一致性。未覆盖的逻辑操作、裁剪等功能仍需单独评估。

debug 构建还提供真实游戏评估入口：当应用私有目录中存在 `graphics-runtime-evaluation.json` 时，使用上述隔离 provider；JSON 的 `requestGl33` 可为这轮实验指定 3.3 版本声明。创建该文件只允许在已结束会话的调试应用中操作，删除后下一次启动恢复自动能力选择。示例命令（先运行上面的设备探针以准备隔离 provider）：

```sh
printf '%s' '{"requestGl33":true}' | adb -s DEVICE_SERIAL shell "run-as dev.plutocrown.factorio sh -c 'cat > files/graphics-runtime-evaluation.json'"
adb -s DEVICE_SERIAL shell am start -n dev.plutocrown.factorio/com.termux.x11.MainActivity --ez playGame true --ei spriteLoadingLimit 1 --es graphicsPreset compat
# 会话结束后清除实验选择，恢复自动选择。
adb -s DEVICE_SERIAL shell run-as dev.plutocrown.factorio rm files/graphics-runtime-evaluation.json
```

入口代码为 `app/src/debug/java/org/factorio/local/launcher/GraphicsRuntimeEvaluation.java`；release 使用独立的空实现，不读取该文件。

游戏评估仍须检查真实出帧、可见画面、贴图、地图加载与退出。计划及验收顺序见 `docs/GRAPHICS-COMPATIBILITY-PLAN.md`。

## 渲染问题的受控对照

debug 请求可带 `config` 对象，仅允许覆写 `gpu-accelerated-compression`、`gpu-accelerated-mipmap-compression`（字符串 `true` / `false`）和 `texture-compression-level`（`none` / `high-quality`）。启动时将正常生成的配置复制到缓存，再改写副本；原档案的配置不被实验覆盖。移除请求字段后，下轮使用正常配置。

`zinkDebug` 可传入 Zink 自有诊断标志，例如 `sync`、`noreorder`、`norp` 或 `flushsync`。一次只比较一个机制，保存同轮原始日志及画面，再恢复对照状态。release 不读取这些请求。

`ZinkBlendProbe.c` 还覆盖已知色块的 RGBA/BC1/BC3 整图上传、局部更新、PBO、整数纹理到压缩纹理的图像拷贝，以及三层不可变纹理数组。所有读回仅用于探针。真实地形和运动场景仍是发布前的必要验收项。

## 已验证结论

颜色窗口和读回一致；RGBA、BC1、BC3、BC4、BC5、PBO、图像拷贝及纹理数组探针通过。缓冲纹理完整范围曾失败，应用 `texel-buffer-limit.patch` 后，12 项 MRT 像素检查全部通过。正常压缩配置下的真实游戏画面已由用户确认恢复。原因与各项修复的对应关系见 [兼容性计划结论](../../docs/GRAPHICS-COMPATIBILITY-PLAN.md#结论原因与修复方法)。

## 原生故障辅助定位

`tests/device/GnuCrashTrace.c` 是独立的诊断工具，不进入 APK 或普通启动流程。完成上述 GNU 构建准备后，可用相同交叉编译器生成：

```sh
.cache/mesa-tools/bin/cross-cc -O2 tests/device/GnuCrashTrace.c -o .cache/gnu-crash-trace
```

在调试应用 UID 的 GNU 环境中运行 `gnu-crash-trace PROGRAM [ARGS...]`，或显式传入 `--attach PID`。附加模式检查目标 UID；捕获故障时输出信号、寄存器、可用的帧指针及映射，再将原故障信号交还目标。启动子进程的模式在故障后结束自己的子进程；超时只终止自己启动的子进程，不主动杀死附加目标。需自行保存同一轮日志并用匹配的源码或符号解析地址，输出本身不能证明故障发生在哪个组件。设备连接信息始终从命令参数传入。
