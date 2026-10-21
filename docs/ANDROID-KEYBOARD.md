# 安卓软键盘接入

启动器利用游戏内SDL已有的文本输入请求弹出安卓输入法。游戏文件保持原样，不需要替换成掌机版本。当前已针对Linux ARM64 Factorio 2.1.23补充运行接口适配；2.1.22的请求路径也做过静态核对，其他版本需要分别验证。

## 触发和回传

1. 游戏文本框获得焦点后，游戏调用 SDL 文本输入接口。
2. 启动器在引擎启动环境中设置 `SDL_ENABLE_STEAM_SCREEN_KEYBOARD=1` 和 `SDL_ENABLE_SCREEN_KEYBOARD=1`。已核对的2.1.23还会在创建窗口时以最高优先级把第一个开关设为0，因此额外通过SDL提示回调接口维持用户请求的开启状态；仅设置环境变量不足以启用该版本的通道。
3. SDL 发出 `steam://open/keyboard?XPosition=…&YPosition=…&Width=…&Height=…&Mode=…`；结束输入时发出 `steam://close/keyboard`。这里使用的是 SDL 的平台接口。
4. SDL 的 URL 打开路径会尝试桌面 D-Bus portal；当前安卓运行环境没有桌面 portal，随后通过 `PATH` 查找 `xdg-open`。启动器把自己的专用目录放到 `PATH` 前面，用一个小型原生程序接收这两类请求。
5. 原生程序通过当前应用 UID 内的 Unix socket 把显示、收起、区域和输入模式送给游戏窗口。窗口提供 Android `InputConnection`，调用系统输入法。
6. Android使用完整的`Editable`和`InputConnection`维护组合文字、光标及候选替换。组合更新不会直接追加到游戏，确认后只同步与上次已提交文字的差异，避免`ABC`变成`AABABC`。
7. 已适配的2.1.23通过同UID、当前会话认证的私有通道，把UTF-8确认文字和编辑操作送回运行适配器；适配器用`SDL_RunOnMainThread`在游戏SDL主线程调用已有文本/按键事件接口。删除、插入和光标移动按同一条命令依次执行。没有修改游戏文件、函数表或指令。

模式 0 为普通文本，1 为多行，2 为邮箱，3 为数字。输入框坐标用于解析和诊断，当前不会移动游戏画面或绘制候选框。输入法覆盖在游戏之上，游戏 Surface 不随键盘弹出而缩小。

按安卓返回键优先收起可见键盘。输入框仍处于输入状态时，可在“会话”菜单中选择“显示键盘”重新打开。切换到后台会取消未确认的组合文字并收起输入法；返回后按当前输入状态恢复。游戏结束输入时取消未确认组合文字，避免把半截拼音误送给另一个控件。

0.3.5还发现隐藏输入法后游戏点击不响应：触摸事件已经转发，但SDL仍记录屏幕键盘显示中。0.3.7补充实际显示、隐藏状态的回传，调用SDL已有后端接口并保留文本输入活动，不额外发送ESC。后端产生的显示/隐藏URL带私有回声标记，只确认收件、不重复创建Android编辑连接，以保留候选和输入模式。后台暂停期间不向暂停中的引擎发送状态请求。该修复正在进行安装和实际点击验证。

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

生成文件在 `app/build/generated/ime/`。`assets/ime-native.properties`记录程序大小、摘要和源码摘要；构建时核对APK内程序，运行时再次核对安装文件。

另一个由启动器源码编译的文件是`jni/arm64-v8a/librea_ime_hint.so`，对应[sdl-hint-guard.c](../native-input/ime/sdl-hint-guard.c)及[接口说明头文件](../native-input/ime/sdl-hint-interfaces.h)。它是GNU共享库，由游戏的GNU加载器预加载，不加载到安卓Java进程中。核心编译命令是：

```text
zig cc -target aarch64-linux-gnu.2.38 -std=c11 -O2 -shared -fPIC -s \
  -Wall -Wextra -Werror -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 \
  native-input/ime/sdl-hint-guard.c -o librea_ime_hint.so -ldl -pthread
```

这个适配器使用SDL已有的`SDL_AddHintCallback`和`SDL_SetHintWithPriority`接口，在游戏覆盖开关时恢复为1。它还接收确认后的UTF-8文字及编辑命令，在SDL主线程调用既有输入事件接口，保持编辑顺序；线程使用项目内编译器的`-pthread`选项编译。它不替换指令、函数表或游戏文件。当前接口只核对了2.1.23；启动器按引擎身份选择是否启用，并在调用前检查接口位置和指令前缀。其他版本仍可导入和启动，但不加载这份尚未适配的接口代码。新增程序和接口头文件都随启动器源码提供，构建时自动生成，不从网络下载其成品。

原有52个游戏运行库沿用原来的恢复和校验流程，Compose另外附带1个AndroidX库，加上这两个键盘文件，APK共55个原生文件。

musl 的原文许可证随 APK 放在 [IME-MUSL-COPYRIGHT.txt](../app/src/main/assets/IME-MUSL-COPYRIGHT.txt)。原生程序自有代码使用 GPL-3.0-or-later。

## 范围与验证状态

请求通道核对对端 UID 和本次会话标识，只接受两种键盘 URI 及规定的数字字段。会话标识不写入诊断报告；诊断仅记录请求次数、模式、可见状态和提交字符数量，不记录输入的文字。

Windows 本地构建通过，8组共198项主机检查通过，其中新增27项请求解析和组合文字检查。标准 APK 为31,386,537字节，平板同签名升级包为36,531,917字节，已保留原游戏与配置完成升级。7,160字节原生程序在平板应用 UID 下通过符号链接执行和 socket 通信检查。另用安装包中的真实请求接收器验证了4种输入模式、收起请求，以及错误会话标识和缺失字段拒绝；该项未启动游戏或弹出输入法。

随后在太空时代2.1.23发现：游戏会在创建窗口时以最高优先级关闭键盘开关。0.3.4补充SDL提示回调后，玩家确认输入法能够自动弹出，但中文和英文候选提交仍失败，出现前缀重复。

0.3.5改用完整Android编辑连接，并补充SDL UTF-8/编辑回传。Windows本地223项主机检查通过；在平板上从候选APK加载真实编辑连接，18项Android运行测试通过，覆盖逐步候选、候选替换、中文、emoji、批量编辑、光标及退格，未干扰当时的游戏。该版本已保留数据升级并通过正常启动按钮重新运行，使用均衡画质、100%分辨率和加载上限1。玩家已在真实太空时代2.1.23“载入存档”搜索框确认英文候选不再重复、中文选词能够进入游戏，同一会话确认新SDL通道接收编辑且失败0次。emoji及复杂编辑仍只有组件测试证据。随后发现隐藏输入法后点击不能恢复、需要ESC的情况，正在检查键盘可见状态同步。

这个实现使用文本输入启停请求，不通过屏幕图像猜测输入框，也不读取游戏内部文本内容。编辑连接只维护本次连接中组合和提交的局部文字，无法提供输入框原有的完整内容或游戏光标的精确位置。诊断只记录请求、编辑次数和字符数量，不保存输入文字。其他版本若更换SDL请求或输入接口，需要重新核对适配。
