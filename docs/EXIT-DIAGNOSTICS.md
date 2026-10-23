# 退出判断与日志字段

用户结束会话与原生进程完成全部清理是两个阶段。引擎会在全局对象销毁期间打印 `Goodbye`，之后仍有管理器和资源需要销毁。故障出现在这里时，既应保留原生故障证据，也应正确识别用户已经退出游戏。

| 证据 | 会话结果 | 提示 |
| --- | --- | --- |
| 用户通过会话菜单主动结束 | stopped | 返回启动器 |
| 按顺序出现 user-quit、Goodbye，之前没有运行故障 | normal | 正常退出；后续清理故障保留在诊断中 |
| 明确启动失败，或 Goodbye 前发生原生故障 | failed | 报错并提供日志导出 |
| 没有完成正常退出，进程以非零状态结束 | interrupted | 未正常退出，原因尚未确认；提供日志导出 |
| 进程以 0 结束且没有上述错误 | normal | 正常退出 |
| 未能取得退出状态，也没有明确证据 | unknown | 原因未确认；提供日志导出 |

137 本身不能说明是内存不足、外部终止、引擎故障还是启动器后续清理。报告中的 `terminationRequestedBy=launcher` 与 `terminationReason` 表明启动器发起过终止；`fatalTermination.signal=SIGKILL` 等字段记录实际执行的强制清理。没有这些记录时，不推断具体终止者。

`graphicsInitialization` 明确输出 `userQuitRequested`、`userStopRequested`、`userExitConfirmed`、`normalExitCompleted`、`normalExitEvidenceSource` 和 `shutdownFault`。`normalExitCompleted` 为兼容已有报告保留，表示已识别有序用户退出，不表示原生析构已无故障完成。`faultPhase` 区分 runtime／shutdown；原始 `fatalSignal`、`fatalLine` 和退出码保留。

报告的 `application` 包含实际安装包的包名、版本、版本码与 `exitPolicyVersion`，便于判断读取的是哪一版执行逻辑。没有故障地址和调用栈时，单凭 SIGSEGV 日志无法确定具体缺失或不兼容的 API。

## 导出时刷新与会话隔离

当前会话每次生成或导出归档都会重新读取报告；同名 ZIP 通过临时文件和原子替换更新，不再直接复用旧快照。系统文件管理器读取当前日志 ZIP 时也会刷新。系统保存位置选好后、实际复制前再采集一次本应用的 Android 退出历史，因此等待文件选择器期间产生的新记录也能进入归档。

如果保存位置选择期间已经启动另一个会话，导出仍指向原来选中的会话，不会把新会话的报告或配置目录中的新引擎日志装进旧归档。报告无法读取或刷新失败时，不用失败的半成品替换已有归档。

## 窗口来不及收尾时的证据

游戏窗口建立会话后，诊断文件写入 `files/session-diagnostics/session-<开始时间>/`。引擎工作线程直接保存进程身份、输出尾部和最终结果；不依赖界面线程收到结束回调。完整会话结果也在通知界面之前落盘。ZIP 中的主要新增条目：

| 条目 | 内容 |
| --- | --- |
| `archive-info.json` | 实际采集时间、报告更新时间、是否已取得最终引擎结果 |
| `engine-process.json` | 窗口 PID、引擎 PID、启动时间与可读取时的进程启动计数 |
| `engine-result.json` | 进程结束码、原始信号与启动器终止原因等；由引擎监控线程写入 |
| `session-engine-result.json` | 完整会话结果，含退出分类与游戏日志证据 |
| `engine-stdout.log` | 持续写入的标准输出／错误尾部，即使界面报告未更新也可读取 |
| `memory-samples.jsonl` | 约每秒一次的引擎和窗口进程状态，以及系统可用内存、交换空间和可读取的内存压力信息 |
| `memory-summary.json` | 已采到的引擎 RSS 峰值、观察到的 VmHWM、系统可用内存低值及采样次数；单位 KiB |
| `window-termination.json` | 窗口因 Activity 销毁而请求结束自身的标记；与引擎异常分开 |

`factorio-current.log` 优先从当前会话的实际日志读取；缺少可用文件时，退回报告中的引擎日志或输出尾部。不会仅因进度报告没有 `writeData` 字段就丢掉全部引擎输出。

输出和内存历史每份使用最多两个 1 MiB 分段，导出各自最多保留最后 1 MiB；采样 JSONL 在截断时保留完整行。最多保留 20 份会话诊断目录和 20 个归档。JSON 摘要采用原子替换，输出按块持续落盘，不为每块执行磁盘同步。采集失败只记录诊断错误，不把游戏会话判为失败。

窗口或整个应用被系统直接杀死时，仍可能无法写出最后的退出码；此时只能读取此前落盘的尾部、采样和后续 Android 退出历史。每秒采样可能漏掉瞬间峰值，RSS 不等于完整 GPU 内存，Android 退出记录可能只覆盖启动器／窗口而没有 GNU 引擎子进程。因此缺少最终证据时继续标为原因未确认，不仅凭 137 或窗口自身的 SIGKILL 判定 OOM。
