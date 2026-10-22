# 安卓软键盘接入

启动器利用游戏内 SDL3 的文本输入请求调用 Android 输入法，再通过 SDL3 接口把确认文字和编辑操作送回游戏。游戏文件保持原样。适配器在运行时解析接口，不绑定某个游戏版本；能否启用取决于导入客户端所用的 SDL3、符号可见性和接口行为。

## 触发和回传

1. 游戏文本框获得焦点后调用 SDL 文本输入接口。
2. 启动环境设置 `SDL_ENABLE_STEAM_SCREEN_KEYBOARD=1` 和 `SDL_ENABLE_SCREEN_KEYBOARD=1`。适配器通过 SDL 提示回调维持屏幕键盘请求开关，以处理客户端在窗口创建期间覆盖该值的情况。
3. SDL 的兼容请求路径发出 `steam://open/keyboard?XPosition=…&YPosition=…&Width=…&Height=…&Mode=…`；结束输入时发出 `steam://close/keyboard`。这条路径不要求安装 Steam 客户端。
4. 运行环境通过 `PATH` 找到启动器提供的 `xdg-open` 辅助程序。辅助程序只接受规定的键盘 URI，通过应用 UID 内的 Unix socket 将显示、收起、区域和输入模式送到 Android 游戏窗口。
5. 窗口提供 Android `InputConnection`，通过完整的 `Editable` 维护组合文字、光标和候选替换。组合更新不直接追加到游戏，确认后同步与此前已提交文字的差异。
6. 当前会话认证的私有通道把 UTF-8 确认文字及编辑操作送回原生适配器。适配器通过 `SDL_RunOnMainThread` 在 SDL 主线程调用文本／按键事件接口，依次处理删除、插入与光标移动。
7. Android 输入法的显示／隐藏状态回传到 SDL 后端。私有回声标记同时写入 SDL 缓存环境和实际进程环境，以覆盖不同子进程环境生成方式；后端调用结束即还原。带该标记的请求只确认接收，不重复建立 Android 编辑连接；后台暂停时不向暂停引擎派发状态操作。

模式 0 为普通文本，1 为多行，2 为邮箱，3 为数字。输入框坐标用于解析和诊断，当前不会移动游戏画面或绘制候选框。输入法覆盖在游戏之上，游戏 Surface 不随键盘弹出而缩小。

按 Android 返回键优先收起可见键盘；输入框仍处于输入状态时，可在“会话”菜单选择“显示键盘”重新打开。切换到后台会取消未确认组合文字并收起输入法；返回后按当前输入状态恢复。游戏结束输入时取消未确认组合文字，避免送给其他控件。

## 原生接收程序如何获取

新增的 `librea_ime_opener.so` 由 [keyboard-opener.c](../native-input/ime/keyboard-opener.c) 编译，属于启动器代码。它虽然以 `.so` 文件名打包，实际是静态 ARM64 Linux 可执行程序；不会由 `System.loadLibrary` 加载，也不依赖安卓动态链接器。安装后，启动器在应用私有缓存目录建立 `xdg-open` 符号链接，指向 APK 提取出的这个程序。

编译使用 Zig 0.15.2 自带的 musl：

- Windows x86_64：[zig-x86_64-windows-0.15.2.zip](https://ziglang.org/download/0.15.2/zig-x86_64-windows-0.15.2.zip)。解压到工程 `tools/`，使 `tools/zig-x86_64-windows-0.15.2/zig.exe` 存在。构建脚本会核对编译器身份。
- Linux x86_64：[zig-x86_64-linux-0.15.2.tar.xz](https://ziglang.org/download/0.15.2/zig-x86_64-linux-0.15.2.tar.xz)。构建时按固定 URL 自动下载到工程 `.cache/`、校验并解压到 `tools/`。
- macOS Apple Silicon：[zig-aarch64-macos-0.15.2.tar.xz](https://ziglang.org/download/0.15.2/zig-aarch64-macos-0.15.2.tar.xz)。Intel Mac 使用 [zig-x86_64-macos-0.15.2.tar.xz](https://ziglang.org/download/0.15.2/zig-x86_64-macos-0.15.2.tar.xz)。构建时按本机 CPU 自动下载、校验并解压。

版本、下载校验和编译器校验集中在 [ime-compiler.properties](../scripts/ime-compiler.properties)。运行普通构建入口时 Gradle 自动执行 [BuildImeNative.java](../scripts/BuildImeNative.java)，等价的核心编译参数是：

```text
zig cc -target aarch64-linux-musl -std=c11 -O2 -static -s \
  -Wall -Wextra -Werror -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 \
  native-input/ime/keyboard-opener.c -o librea_ime_opener.so
```

生成文件在 `app/build/generated/ime/`。`assets/ime-native.properties` 记录生成程序的大小、摘要和源码摘要，供构建时核对 APK 内产物；运行时检查原生文件存在和 ELF 架构。

另一个由启动器源码编译的文件是`jni/arm64-v8a/librea_ime_hint.so`，对应[sdl-hint-guard.c](../native-input/ime/sdl-hint-guard.c)及[SDL 符号解析器](../native-input/sdl-symbols.h)。它是GNU共享库，由游戏的GNU加载器预加载，不加载到安卓Java进程中。核心编译命令是：

```text
zig cc -target aarch64-linux-gnu.2.38 -std=c11 -O2 -shared -fPIC -s \
  -Wall -Wextra -Werror -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 \
  native-input/ime/sdl-hint-guard.c -o librea_ime_hint.so -ldl -pthread
```

这个适配器通过符号名称查找 SDL3 接口，不使用游戏摘要、固定地址或指令前缀。屏幕键盘后端所需的视频设备优先从保留下来的 `SDL_GetVideoDevice` 接口取得；该接口被内联时，枚举编译器重命名的 `_this.*` 对象，并用 `SDL_GetCurrentVideoDriver` 返回的驱动名指针核对设备身份，避免把输入／音频模块的同名变量误当成视频设备。这个回退只读取 SDL3 `SDL_VideoDevice` 的首个名称成员，不依赖中间字段偏移；仍属于内部 ABI，布局变化时需要适配，不能把名称匹配当成全部 ABI 都兼容。空、不可读或身份不唯一的候选不会交给后端调用。解析器先查询动态符号，再检查已加载 ELF 模块的符号表，包括静态链接 SDL 所在的 `.symtab`。适配器检查 SDL 大版本，并要求提示回调、主线程调度、文本／按键事件和当前 X11 后端可见状态接口可用。部分接口属于 SDL 内部实现，符号存在并不保证未来版本的 ABI 和行为兼容。缺少必要接口时，适配器记录缺失符号及 `interface_mismatch`，停止启用这条适配路径。

[keyboard-opener.c](../native-input/ime/keyboard-opener.c)、[sdl-hint-guard.c](../native-input/ime/sdl-hint-guard.c) 和手柄适配器 [gamepad-bridge.c](../native-input/gamepad-bridge.c) 均随启动器源码提供，普通 APK 构建会编译它们。无需下载按某个引擎版本生成的适配器。运行库的依赖下载与构建校验仍使用固定版本，详见[原生库说明](NATIVE-LIBRARIES.md)。

musl 的原文许可证随 APK 放在 [IME-MUSL-COPYRIGHT.txt](../app/src/main/assets/IME-MUSL-COPYRIGHT.txt)。原生程序自有代码使用 GPL-3.0-or-later。

## 接口限制与诊断

请求通道核对对端 UID 和本次会话标识，只接受两种键盘 URI 及规定的数字字段。会话标识不写入诊断报告；诊断仅记录请求次数、模式、可见状态和提交字符数量，不记录输入文字。

这个实现依赖 SDL 请求、内部文本事件和 X11 屏幕键盘接口；缺少符号、接口改变或客户端不发出这类请求时，自动弹出和文字回传可能不可用。诊断中的请求、接口匹配和输入失败状态用于区分这些情况。一次构建或组件检查通过，不能证明所有游戏版本、输入法和编辑行为都兼容。

编辑连接只维护本次连接中组合和提交的局部文字，无法提供输入框原有的完整内容或游戏光标的精确位置。它不通过屏幕图像猜测输入框，也不读取游戏内部文本内容。

## 视频设备解析回归

`tests/device/SdlVideoDeviceProbe.c` 是独立 GNU 测试程序，不加载游戏。它包含无关的 `_this` 和被编译器改名的视频对象，覆盖正确辨认、空值、不可读地址、身份冲突和重复别名。用工程 Zig 编译后，通过设备上已有的 GNU loader 运行，不需要重装 APK：

```bash
zig cc -target aarch64-linux-gnu.2.38 -O2 -Wall -Wextra -Werror \
  tests/device/SdlVideoDeviceProbe.c -ldl -o SdlVideoDeviceProbe
```

把产物放入可执行的应用私有缓存，以当前会话的 GNU loader 和 `runtime/gnu` 库路径启动。成功输出 `SDL_VIDEO_DEVICE checks=5 passed=true`。实际输入法验收还需检查 Android 收起后 SDL 隐藏确认、点击恢复、再次输入及组合文字，组件通过不能代替这些行为。
