# 图形黑屏排查与 Mesa 兼容运行库

本说明描述图形初始化、窗口呈现和透明混合的兼容问题，以及项目采用的修复和回归方法。修复不按测试手机型号、CPU 型号或游戏版本号判断是否启用。

问题范围：目前尚未确定受影响的 GPU 型号及驱动版本范围，不能从单台设备的验证结果推断仅该型号受影响或所有 Adreno 都受影响。动态颜色写入补丁按 `VK_DRIVER_ID_QUALCOMM_PROPRIETARY` 生效；这一覆盖范围宽于当前实机验证范围，其他型号和驱动版本仍需验证。

排除了贴图压缩、三维 LUT 数据和纹理拷贝等方向。关闭贴图压缩没有改善画面，纹理内存反而由约 460 MB 增加到 1130 MB，已恢复原设置。最终以透明混合最小复现确定动态颜色写入路径错误。诊断用帧读取、调用跟踪、校验层和临时注入均不进入正式启动逻辑。

## 原因与运行时选择

排查需要分别检查以下四个环节；不同设备不一定同时遇到全部问题：

1. **运行库能力与设备扩展是否匹配。** 当 Mesa 构建要求 `VK_KHR_maintenance5` 而设备驱动未提供该扩展时，EGL 初始化失败。兼容运行库用于绕开这项依赖；是否满足要求取决于驱动实际能力，不能仅凭 Vulkan 版本号判断。
2. **OpenGL 上下文是否能够创建。** Zink 公布的上下文能力与游戏请求不匹配时，可能出现 `EGL_BAD_MATCH`。当前 Adreno 路径使用 `MESA_GL_VERSION_OVERRIDE=3.3`，允许创建游戏请求的上下文。这里的 `3.3` 是实际的图形 API 配置，不是测试软件版本；该设置不代表驱动完整符合对应 API 的全部功能要求，仍需检查渲染结果。
3. **交换链与窗口透明度是否匹配。** 原 WSI 在设备同时支持 RGBA/BGRA AHB 格式时只公布 `INHERIT`，Kopper 请求 `OPAQUE` 会导致交换链被拒绝。自有 WSI 与配套 Surface 接收器一起提供 OPAQUE：RGBA 使用 RGBX 原生缓冲，BGRA 在 GPU 采样输出时固定 alpha 为 1，避免游戏输出的透明度让整个画面被 Android 合成器隐藏。同步、释放栅栏、AHB 和 GPU 采样路径保持不变。
4. **透明混合是否保留正确的目标颜色。** 已复现 Zink 与 Qualcomm 专有 Vulkan 驱动配合使用 `VK_EXT_color_write_enable` 时丢失目标颜色的问题。RGBA8 离屏缓冲先清为红色，再用 `ZERO / ONE_MINUS_SRC_ALPHA` 混合 alpha=0.25 的片元，预期得到 `191,0,0,255`，故障路径实际为 `0,0,0,0`。补丁在 Qualcomm 专有驱动上关闭 Zink 的动态颜色写入能力，使用静态管线颜色掩码，回归结果恢复到预期值。现有证据定位了出错路径，尚不足以将责任完全归于驱动内部或 Zink，也未界定受影响的型号和驱动版本。

检测到 `/vendor/lib64/hw/vulkan.adreno.so` 时自动选择兼容运行库。在 Vulkan 功能查询成功且缺少 dynamic rendering 或 nullDescriptor 时，优先选择保留传统路径的 Mesa 23.1.9；其余情况保留现有的 Adreno / 基线路径。查询失败不拒绝游戏启动，并保留诊断。`GraphicsDriver` 统一识别常见驱动文件，供运行库选择和环境适配使用；文件存在性不等于完整图形能力。各条运行库共用当前源码生成的 ICD / Android Surface 接收器及相同呈现协议。48 个第三方原版运行库继续保留；APK 另带六个当前源码生成的自有组件、六个兼容 Mesa provider 及两个自有适配组件的兼容文件名，加上 AndroidX 路径库，再加五个传统 Mesa provider，共 68 个原生文件。

运行库在启动游戏前直接选定，每次仍只启动一个游戏进程。兼容库已随 APK 打包，不需要在用户手机上下载或编译。`MESA_GL_VERSION_OVERRIDE=3.3` 用于 Adreno 和传统 Mesa 路径；Qualcomm 动态颜色写入补丁只按 Qualcomm 专有 Vulkan 驱动 ID 生效。Mali 原有的 hybris 环境适配保留。诊断记录 `compatibleMesa`、`legacyMesa`、`vulkanCapabilities`、`graphicsDriver` 和 `adrenoCompatibility`。

已移除“Mali 图形兼容模式”：它只在两套 Mesa 之间切换，不能补齐驱动缺少的 dynamic rendering 与 robustness2 功能，不再作为用户设置提供。受限驱动的实际根因、修复结论和验收记录见 [图形兼容计划](GRAPHICS-COMPATIBILITY-PLAN.md)。诊断采集、导出和已有有效的透明混合修复保留。

兼容 provider 从固定的 `taowen/mesa` 源码构建，并应用工程内的 `native-input/mesa-compat/qualcomm-static-color-write.patch`。依赖版本、源码提交、构建工具和 Debian 头文件包的下载地址与 SHA-256 记录在 [构建输入清单](../native-input/mesa-compat/inputs.json)。这些版本标识用于复现和校验构建，不是游戏或设备兼容白名单。Debian 包只解包开发头文件，不运行安装脚本；链接使用工程已核验的 GNU 运行库。

## 构建和复用

完整步骤见 [从源码还原启动器自有组件](SOURCE-BUILD.md#修改过的-mesa从源码生成六个-provider)。macOS/Linux 使用 `python3 scripts/build-mesa-runtime.py --providers-only` 从固定源码和工程内补丁生成六个 GNU Mesa provider，不需要 Android SDK/NDK。Windows 复用自己生成的 `.cache/mesa-providers.zip`，并通过 `BuildImeNative.java` 从当前源码重建 ICD 与 Android 接收器；`BuildMesaNative.java` 将两部分组成完整兼容运行库。更改自有适配器不再要求重编整套 Mesa。

预编译 ZIP 只是缓存，不是必须从他处获取的输入。第三方头文件及编译工具的固定来源在构建脚本和输入清单中；Android 侧不再链接从测试设备提取的系统库。源码重建并不需要设备连接。

### GitHub Actions 如何取得新增库

云端 Ubuntu 构建调用 `scripts/build.sh`，通过 Gradle 的任务依赖完成下面的流程，不要求旧运行库附件包含高通兼容修复：

1. `restoreRuntime` 从固定运行库附件恢复 48 个第三方原版库及 XKB 数据。
2. `buildImeNative` 使用当前源码生成自有输入、TLS、ICD 和 Android Surface 接收器组件。
3. `buildMesaNative` 在没有可用 provider 缓存时调用 `build-mesa-runtime.py --providers-only`，从公开 Mesa 源码和工程内补丁生成六个兼容 provider；同时将本次编译的 ICD、接收器以兼容文件名纳入输出。
4. `BuildMesaNative` 还调用 `BuildLegacyMesaNative.java`，无有效缓存时从源码构建传统 Mesa 的五个 provider，见 [传统路径构建说明](../native-input/mesa-legacy/README.md)。
5. `stageGameRuntime` 将两套新增 provider 加入 APK 的原生库输入目录；`BuildLauncher.verifyPayload()` 检查八个兼容文件、五个传统 provider、自有组件及第三方依赖均已打包。

八个兼容文件为 `librea_compat_mesa_{gallium,egl,glx,gbm,gbm_dri,dril}.so`、`librea_compat_direct_icd.so` 和 `librea_compat_direct_present.so`。它们不需要加入最初的第三方运行库附件。生成或载荷检查失败时构建会停止，不会继续发布缺少这些文件的 APK。此处描述构建源码路径，不代表某次云端构建已经成功。

## 透明混合设备回归

完成一次本地构建，在已连接设备上运行 debuggable 启动器并启动游戏，再执行：

```bash
python3 scripts/check-adreno-blend.py --serial DEVICE_SERIAL
```

脚本复用当前会话的 GNU 运行库和私有 X 服务，编译、运行 `tests/device/ZinkBlendProbe.c`；它不操作游戏界面或档案。设备必须允许 `run-as`，正式 release APK 不支持该诊断入口。可用 `--mesa-directory DEVICE_PATH` 对同一探针做运行库 A/B 测试。原运行库输出 FAIL，补丁库输出 `before=255,0,0,255 after=191,0,0,255 ... PASS`。探针的像素读取仅用于回归测试，正式游戏呈现仍使用 GPU AHB 路径。

## 启动失败诊断

诊断导出的文件结构、会话隔离、去重及证据边界见 [会话日志与诊断导出](SESSION-DIAGNOSTICS.md)。优先查看 `summary.json` 和 `archive-info.json`，再对照本次会话的 `mesa.log`、`engine-stdout.log` 与 `direct-present-report.txt`。

`StartupFailure` 只读取本次启动追加的游戏日志。确认窗口或 OpenGL 上下文创建失败后，启动器结束自己的失败子进程、保留诊断并返回主页，避免错误对话框藏在 Surface 后而长期黑屏。历史日志不会终止新会话。修复了会话采样计数器被重复递增导致 `outputTail` 一直无法更新的问题。

主机回归覆盖历史日志隔离、普通启动不误报、EGL 窗口失败、OpenGL 上下文失败。设备验证分别检查游戏初始化日志、`direct-present-report.txt` 中的真实 AHB 出帧，以及屏幕画面；单独的 EGL/进程成功不作为游戏画面成功证据。

## 缺少 dynamic rendering 的驱动

游戏使用 OpenGL，而较新 Zink 的 Vulkan 实现要求 dynamic rendering 和空描述符；缺少这些能力时，旧的“Mali 兼容”切换无法解决初始化失败。传统路径恢复传统 render pass / 描述符实现，并修复 FIFO 选择、AHB 窗口颜色格式、WSI 输入附件用途协商，以及纹素缓冲上限的 32 位乘法溢出。最后一项溢出会使完整缓冲视图被裁成零长度，造成地形缺失、条纹及运动残影。

正常压缩配置下的真实画面已确认恢复；不保留“关闭压缩”或“强制同步”等无效实验配置。详细证据和边界见 [结论与修复方法](GRAPHICS-COMPATIBILITY-PLAN.md#结论原因与修复方法)。这不代表所有 Vulkan 驱动均已完成验证。
