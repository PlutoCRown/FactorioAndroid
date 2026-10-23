# 虚拟手柄

在「启动设置」打开「虚拟手柄」，下一次启动游戏生效。开关默认关闭，按键使用半透明背景。屏幕两侧显示控制区，中间与控制区以外的画面仍可使用原有触屏鼠标；顶部工具条可隐藏两侧控制区、展开扩展键或查看帮助。

## 操作范围

提供 SDL3 完整手柄输入：21 个数字按键、两个双轴模拟摇杆和两个扳机轴。按键包含 ABXY、十字键、LB/RB、L3/R3、Back/Start/Guide、M1、P1–P4 和 TP。LT/RT 可按住，也可通过旁边的 ◇ 锁定，锁定时显示 ●，再次点击松开。锁定与手动按住分别持有状态，松开其中一个不会取消另一个。

实际动作由游戏中的手柄绑定决定，启动器不覆盖这些绑定。官方默认操作包括：左摇杆移动、右摇杆光标/瞄准、ABXY 操作/建造/采矿/背包、十字键旋转等、Back 地图、Start 菜单、LB 快捷面板、P4 蓝图库；LT/RT 与其他键组合完成研究、缩放、驾驶、武器切换等。游戏版本和用户绑定可能不同，可在游戏「设置 → 控制」中查看或重新绑定。扩展键用于保留完整控制器输入范围，并不代表游戏为每个键都设置了默认动作。

双摇杆与各按键可多指同时操作。移动摇杆时有径向死区和圆形限幅，松手回中。隐藏控制区、切换布局、应用失去焦点、切到后台、Surface 销毁、输入法打开和会话结束都会释放触控状态。隐藏后顶部工具条仍保留，方便重新显示。

## 临时切换输入方式

启用时，只将游戏配置 `[input]` 中的 `input-method` 临时改成 `game-controller`。修改前在配置旁保存 `virtual-gamepad-input.properties` 恢复记录。会话结束后恢复原字段；其他游戏写回的画质、音量和绑定保持不变。原来就使用手柄模式时不修改它。游戏中明确切回键鼠模式时尊重该选择。

进程意外中断时，下次准备游戏配置会先处理恢复记录，再根据本次开关决定是否启用。关闭功能且不存在恢复记录时不会写入输入方式。文件格式处理沿用实际 INI 分节，不绑定游戏版本、引擎哈希或配置格式头。

## 实现与构建

控制层自行使用项目已有的 Jetpack Compose 实现，没有增加外部摇杆库。源码和构建输入：

| 文件 | 用途 |
| --- | --- |
| `app/src/main/kotlin/org/factorio/local/launcher/ui/controls/VirtualGamepadOverlay.kt` | 三个有界 ComposeView、布局、摇杆绘制、多指按住、扳机锁定与帮助 |
| `app/src/main/java/org/factorio/local/launcher/input/VirtualGamepadState.java` | 按键所有权、模拟轴范围、死区和物理手柄合并 |
| `app/src/main/java/org/factorio/local/launcher/input/GamepadEventQueue.java` | 保留按下/松开及扳机变化，只合并相邻、按键状态相同的摇杆移动 |
| `app/src/main/java/org/factorio/local/launcher/ControllerRelay.java` | 与原有物理手柄共用应用私有套接字，发送 SDL 状态 |
| `app/src/main/java/org/factorio/local/launcher/VirtualGamepadConfig.java` | 临时输入方式覆盖与中断恢复 |
| `native-input/gamepad-bridge.c` | 通过公开 SDL3 接口创建虚拟手柄，每个数据包调用 SDL_UpdateJoysticks 提交状态 |
| `scripts/BuildImeNative.java` | 从上述 C 源码生成 librea_gamepad_bridge.so，正式 APK 构建自动调用 |

物理手柄与触控状态分别保存：按钮取并集、扳机取较大值、每个摇杆按向量长度选择一组输入，避免一方松手清掉另一方。发送队列有上限；溢出时清除积压并释放虚拟输入，断线后不重放旧按键。原生层仍通过已加载模块的公开 SDL3 符号解析接口，不修改游戏二进制。

三个 ComposeView 分别约束在左右控制区和顶部工具条内；没有用全屏透明 View 拦截其他游戏触摸。布局根据可用窗口、系统栏和刘海区域缩放，并为右下角会话菜单留出空间。

常规构建命令见 [工程结构与开发约定](DEVELOPMENT.md)。单元测试在 `VirtualGamepadTests`、`VirtualGamepadConfigTests` 中，归入现有 touch/config 测试组，覆盖默认关闭、多输入源合并、松开与锁定、轴范围、快速点击、队列溢出和配置恢复。完整多指操作与游戏实际绑定仍需在设备上验证。

参考官方接口：[Factorio 手柄 FAQ](https://forums.factorio.com/viewtopic.php?t=106661)、[手柄操作设计](https://www.factorio.com/blog/post/fff-370)、[SDL3 虚拟手柄](https://wiki.libsdl.org/SDL3/SDL_AttachVirtualJoystick)、[提交虚拟输入](https://wiki.libsdl.org/SDL3/SDL_UpdateJoysticks)。
