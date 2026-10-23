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
