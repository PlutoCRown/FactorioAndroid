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

检测到 `/vendor/lib64/hw/vulkan.adreno.so` 时选择兼容运行库。其他设备继续选择基线 Mesa 和原 ICD。这个文件检查是当前的运行库选择策略，不等于对驱动完整能力的检测。48 个第三方原版运行库继续保留；APK 另带六个当前源码生成的自有组件、六个兼容 Mesa provider 及两个自有适配组件的兼容文件名，加上 AndroidX 路径库，共 63 个原生文件。

兼容 provider 从固定的 `taowen/mesa` 源码构建，并应用工程内的 `native-input/mesa-compat/qualcomm-static-color-write.patch`。依赖版本、源码提交、构建工具和 Debian 头文件包的下载地址与 SHA-256 记录在 [构建输入清单](../native-input/mesa-compat/inputs.json)。这些版本标识用于复现和校验构建，不是游戏或设备兼容白名单。Debian 包只解包开发头文件，不运行安装脚本；链接使用工程已核验的 GNU 运行库。

## 构建和复用

完整步骤见 [从源码还原启动器自有组件](SOURCE-BUILD.md#修改过的-mesa从源码生成六个-provider)。macOS/Linux 使用 `python3 scripts/build-mesa-runtime.py --providers-only` 从固定源码和工程内补丁生成六个 GNU Mesa provider，不需要 Android SDK/NDK。Windows 复用自己生成的 `.cache/mesa-providers.zip`，并通过 `BuildImeNative.java` 从当前源码重建 ICD 与 Android 接收器；`BuildMesaNative.java` 将两部分组成完整兼容运行库。更改自有适配器不再要求重编整套 Mesa。

预编译 ZIP 只是缓存，不是必须从他处获取的输入。第三方头文件及编译工具的固定来源在构建脚本和输入清单中；Android 侧不再链接从测试设备提取的系统库。源码重建并不需要设备连接。

## 透明混合设备回归

完成一次本地构建，在已连接设备上运行 debuggable 启动器并启动游戏，再执行：

```bash
python3 scripts/check-adreno-blend.py --serial DEVICE_SERIAL
```

脚本复用当前会话的 GNU 运行库和私有 X 服务，编译、运行 `tests/device/ZinkBlendProbe.c`；它不操作游戏界面或档案。设备必须允许 `run-as`，正式 release APK 不支持该诊断入口。可用 `--mesa-directory DEVICE_PATH` 对同一探针做运行库 A/B 测试。原运行库输出 FAIL，补丁库输出 `before=255,0,0,255 after=191,0,0,255 ... PASS`。探针的像素读取仅用于回归测试，正式游戏呈现仍使用 GPU AHB 路径。

## 启动失败诊断

`StartupFailure` 只读取本次启动追加的游戏日志。确认窗口或 OpenGL 上下文创建失败后，启动器结束自己的失败子进程、保留诊断并返回主页，避免错误对话框藏在 Surface 后而长期黑屏。历史日志不会终止新会话。修复了会话采样计数器被重复递增导致 `outputTail` 一直无法更新的问题。

主机回归覆盖历史日志隔离、普通启动不误报、EGL 窗口失败、OpenGL 上下文失败。设备验证分别检查游戏初始化日志、`direct-present-report.txt` 中的真实 AHB 出帧，以及屏幕画面；单独的 EGL/进程成功不作为游戏画面成功证据。
