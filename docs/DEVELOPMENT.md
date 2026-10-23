# 工程结构与开发约定

## 源码布局

| 目录 | 职责 |
| --- | --- |
| `app/src/main/java/org/factorio/local/launcher/` | Android 启动入口、导入、档案配置、运行环境、会话和日志 |
| `app/src/main/java/com/termux/x11/` | X11 原生接口边界、游戏 Surface、键鼠触控和输入法连接 |
| `app/src/main/kotlin/org/factorio/local/launcher/LauncherUi.kt` | Activity 与 Compose 之间的状态、动作适配 |
| `app/src/main/kotlin/org/factorio/local/launcher/ui/` | 页面布局、主页、启动设置、设备信息，以及页面状态和动作回调 |
| `app/src/main/kotlin/org/factorio/local/launcher/ui/components/` | 通用控件、按钮和 Panel 绘制、会话按钮共用的 Drawable |
| `app/src/main/kotlin/org/factorio/local/launcher/ui/theme/` | Compose 配色和主题 |
| `app/src/test/java/` | Gradle 本地单元测试，目录与 Java 包名对应 |
| `compile-stubs/src/main/java/` | 仅编译期使用的 JNI 注解，不进入 APK |
| `native-input/` | 从源码生成的原生适配组件，以及所需的上游源码和头文件 |
| `scripts/` | 跨平台构建入口、依赖恢复、原生组件编译与开发辅助工具 |
| `tests/device/` | 手动执行的设备探针，不加入本地单元测试或正式 APK |
| `build.gradle.kts` | 格式化规则；格式化模式仅需 JDK，不加载 Android 插件 |
| `docs/` | 构建、运行库来源、功能说明与版本说明 |

Compose 页面通过 `LauncherActions` 调用 Activity 的现有操作；导入、配置写入和会话生命周期仍由 Java 层处理。`LauncherState` 保存展示状态，公共控件不读取 Activity 的私有配置。

`com.termux.x11` 中部分类型和方法由原生运行库按名称查找。调整这一边界时，需要同时核对 JNI 注册、原生方法签名及对应构建输入，不能仅为目录美观改变绑定名称。

构建产物放在 `build/`、`app/build/` 和 `artifacts/`；下载缓存放在 `.cache/`；本地工具和签名材料分别放在 `tools/`、`.local/`。这些目录不提交。运行库的来源与重建入口见 [SOURCE-BUILD.md](SOURCE-BUILD.md)。

## 格式化

Java 使用 google-java-format 1.24.0 的 AOSP 风格，Kotlin 和 Gradle Kotlin DSL 使用 ktfmt 0.53 的 Kotlin 风格，均使用四空格缩进。Spotless 8.0.0 统一提供执行和检查入口；版本固定在 `build.gradle.kts`。

格式化模式只加载工具，不加载 Android 模块。已有 JDK 17 或更新版本即可执行，不需要安装 Android SDK：

```sh
./gradlew -PformatOnly=true formatApply
./gradlew -PformatOnly=true formatCheck
```

Windows PowerShell 使用：

```powershell
.\gradlew.bat -PformatOnly=true formatApply
.\gradlew.bat -PformatOnly=true formatCheck
```

已配置 Android SDK 时，也可以在主工程执行 `formatApply`、`formatCheck`。正式打包入口会执行 `formatCheck`，格式不符合时报告问题；需要修改文件时显式执行格式化命令。

格式化范围是应用 Java/Kotlin、单元测试、Java 构建工具、设备 Java 探针、编译期注解与 Gradle Kotlin DSL。不会重写游戏配置底稿、第三方原生源码和头文件、二进制、资源、下载缓存或生成产物。`.editorconfig` 供编辑器统一编码、换行和缩进；游戏配置底稿保留原有空白。

上游工具说明：[Spotless](https://github.com/diffplug/spotless/tree/gradle/8.0.0/plugin-gradle)、[google-java-format](https://github.com/google/google-java-format/tree/v1.24.0)、[ktfmt](https://github.com/facebook/ktfmt/tree/v0.53)。

## 检查与构建

使用已配置的 Android 工具链执行本地单元测试：

```sh
./gradlew :app:testStandardDebugUnitTest
```

测试源码直接从 `app/src/test/java/` 编译，保持归档、配置、图形退出判断、触控鼠标、文件操作、语言、输入法和导入锁八组回归检查。测试专用的 `android.content.Context` 位于同一测试 source set，仅用于已有的隔离文件测试，不进入应用。

完整构建仍使用 [README](../README.md#开发指南) 中的 `scripts/build-windows.ps1` 或 `scripts/build.sh`；入口执行格式检查、测试、打包，以及签名和运行库检查。设备探针按各功能文档单独运行。
