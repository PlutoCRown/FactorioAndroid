# 安卓软键盘接入

启动器利用游戏内 SDL 已有的文本输入请求弹出安卓输入法。游戏文件保持原样，不需要替换成掌机版本。本实现针对 Linux ARM64 Factorio 2.1.22 的 SDL 3.4.14 输入路径；其他版本需要重新验证。

## 触发和回传

1. 游戏文本框获得焦点后，游戏调用 SDL 文本输入接口。
2. 启动器在引擎启动环境中设置 `SDL_ENABLE_STEAM_SCREEN_KEYBOARD=1` 和 `SDL_ENABLE_SCREEN_KEYBOARD=1`，启用 SDL X11 自带的屏幕键盘请求。
3. SDL 发出 `steam://open/keyboard?XPosition=…&YPosition=…&Width=…&Height=…&Mode=…`；结束输入时发出 `steam://close/keyboard`。这里使用的是 SDL 的平台接口。
4. SDL 的 URL 打开路径会尝试桌面 D-Bus portal；当前安卓运行环境没有桌面 portal，随后通过 `PATH` 查找 `xdg-open`。启动器把自己的专用目录放到 `PATH` 前面，用一个小型原生程序接收这两类请求。
5. 原生程序通过当前应用 UID 内的 Unix socket 把显示、收起、区域和输入模式送给游戏窗口。窗口提供 Android `InputConnection`，调用系统输入法。
6. 拼音等组合文字留在安卓输入法侧，确认文字后以 UTF-8 经现有 X11 Unicode 输入接口送回游戏。删除、回车和方向键经现有按键通道回传。

模式 0 为普通文本，1 为多行，2 为邮箱，3 为数字。输入框坐标用于解析和诊断，当前不会移动游戏画面或绘制候选框。输入法覆盖在游戏之上，游戏 Surface 不随键盘弹出而缩小。

按安卓返回键优先收起可见键盘。输入框仍处于输入状态时，可在“会话”菜单中选择“显示键盘”重新打开。切换到后台会取消未确认的组合文字并收起输入法；返回后按当前输入状态恢复。游戏结束输入时取消未确认组合文字，避免把半截拼音误送给另一个控件。

## 原生接收程序如何获取

新增的 `librea_ime_opener.so` 由 [keyboard-opener.c](../native-input/ime/keyboard-opener.c) 编译，属于启动器代码。它虽然以 `.so` 文件名打包，实际是静态 ARM64 Linux 可执行程序；不会由 `System.loadLibrary` 加载，也不依赖安卓动态链接器。安装后，启动器在应用私有缓存目录建立 `xdg-open` 符号链接，指向 APK 提取出的这个程序。

编译使用 Zig 0.15.2 自带的 musl：

- Windows x86_64：[zig-x86_64-windows-0.15.2.zip](https://ziglang.org/download/0.15.2/zig-x86_64-windows-0.15.2.zip)。解压到工程 `tools/`，使 `tools/zig-x86_64-windows-0.15.2/zig.exe` 存在。构建脚本会核对编译器身份。
- Linux x86_64：[zig-x86_64-linux-0.15.2.tar.xz](https://ziglang.org/download/0.15.2/zig-x86_64-linux-0.15.2.tar.xz)。构建时按固定 URL 自动下载到工程 `.cache/`、校验并解压到 `tools/`。

版本、下载校验和编译器校验集中在 [ime-compiler.properties](../scripts/ime-compiler.properties)。运行普通构建入口时 Gradle 自动执行 [BuildImeNative.java](../scripts/BuildImeNative.java)，等价的核心编译参数是：

```text
zig cc -target aarch64-linux-musl -std=c11 -O2 -static -s \
  -Wall -Wextra -Werror -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 \
  native-input/ime/keyboard-opener.c -o librea_ime_opener.so
```

生成文件在 `app/build/generated/ime/`：`jni/arm64-v8a/librea_ime_opener.so` 和 `assets/ime-native.properties`。后者记录程序大小、摘要和源码摘要；构建时核对 APK 内程序，运行时再次核对安装文件。原有 52 个游戏运行库沿用原来的恢复和校验流程，Compose 另外附带 1 个 AndroidX 库，加上这个键盘程序，APK 共 54 个原生文件。

musl 的原文许可证随 APK 放在 [IME-MUSL-COPYRIGHT.txt](../app/src/main/assets/IME-MUSL-COPYRIGHT.txt)。原生程序自有代码使用 GPL-3.0-or-later。

## 范围与验证状态

请求通道核对对端 UID 和本次会话标识，只接受两种键盘 URI 及规定的数字字段。会话标识不写入诊断报告；诊断仅记录请求次数、模式、可见状态和提交字符数量，不记录输入的文字。

Windows 本地构建通过，8组共198项主机检查通过，其中新增27项请求解析和组合文字检查。标准 APK 为31,386,537字节，平板同签名升级包为36,531,917字节，已保留原游戏与配置完成升级。7,160字节原生程序在平板应用 UID 下通过符号链接执行和 socket 通信检查。另用安装包中的真实请求接收器验证了4种输入模式、收起请求，以及错误会话标识和缺失字段拒绝；该项未启动游戏或弹出输入法。

随后在太空时代2.1.23进行了真实输入框测试：玩家报告没有弹出安卓输入法，接收器请求计数为0。两个SDL开关和会话环境已经传入引擎；原生程序的独立执行与通信通过，仍不能代替真实游戏触发验证。当前自动触发尚未修复，中文提交、删除/回车、返回收起及后台恢复也未完成验收。

这个实现使用文本输入启停请求，不通过屏幕图像猜测输入框，也不读取游戏内部文本内容。无法提供游戏光标前后的完整文字，因此输入法的选择、跨已提交文字重组和精确候选位置能力有限。其他版本若更换 SDL URL 路径或不发送文本输入请求，需要重新检查。
