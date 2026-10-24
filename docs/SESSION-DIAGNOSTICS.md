# 会话日志与诊断导出

导出包先读 `summary.json`，再读 `archive-info.json`。前者集中展示应用与游戏版本、实际选择的 Mesa、图形初始化状态、引擎退出证据和采样内存；后者明确列出诊断文件是否缺失、为空、被截断、读取失败或不属于该会话。退出码 137 本身不能证明 OOM，启动器清理、窗口进程清理和最初的错误是不同证据。

## 关键证据

| 文件 | 用途与边界 |
| --- | --- |
| `session-report.json` | 窗口、Surface、输入、生命周期及会话状态；完整引擎结果改用归档内文件引用 |
| `engine-result.json` | 引擎退出码、启动错误、致命信号、主动终止请求；合并会话结束时补充的退出证据 |
| `session-engine-result.json` | 启动环境准备、依赖与版本检查、运行库选择；重复的图形执行结果引用 `engine-result.json` |
| `engine-progress.json` | 尚无完整会话结果时保存进度信息，不能当作最终退出结果 |
| `factorio-current.log` | 游戏日志；不会用 stdout 冒充缺失的游戏日志 |
| `engine-stdout.log` | 引擎输出及图形库/驱动加载错误；保留与游戏日志的独立来源 |
| `mesa.log` | 本次会话的 Mesa/EGL/Zink 错误，补足 SDL 汇总错误的下一层信息 |
| `direct-present-report.txt` | Android 画面接收端的初始化、Surface、AHB 接收及帧数等证据；有帧记录不等于画面正确 |
| `engine-process.json` | PID、启动标识和开始/结束状态；结束后不再一直记录为 running |
| `session-events.jsonl` | 带时间戳的阶段变化，过滤连续重复阶段，便于定位启动或退出卡住的位置 |
| `memory-samples.jsonl`、`memory-summary.json` | 进程内存与系统可用内存的有限采样，不覆盖全部 GPU 分配，也可能错过最终峰值 |
| `last-process-exits.json` | 仅保留本会话时间范围内、已知相关 PID 的本包 OS 退出记录；没有记录不能证明正常退出 |
| `window-termination.json` | 窗口进程主动清理意图，单独保存，不解释为引擎 OOM |

Mesa 和画面接收端直接写入各自的 `session-diagnostics/session-<epoch>/`，避免下一次启动覆盖这次图形故障。对旧版的全局日志只接受当前会话开始后更新的文件；选择旧归档时不会读取较新会话。每项文本日志最多导出 1 MiB 尾部，JSON 文件上限 2 MiB；滚动日志的 JSONL 尾部仅保留完整行。可选文件缺失或损坏不会抹去其他可用诊断，状态会写入清单。

每次会话额外记录无需创建 GPU 上下文的设备基本信息和采集时间。`device-report.json` 则是用户上次执行的 Android EGL/GLES 检查，可能早于本次会话，其内存数据也不是本次会话内存。包管理器声明的 Vulkan 版本不等于实际 Vulkan 扩展列表，GLES 扩展不等于桌面 OpenGL 能力。当前导出不额外启动 Vulkan/GL 探针；Vulkan loader 的错误和警告通过现有引擎输出采集。

## 去重与信息范围

导出仅处理副本，不改写启动器运行时使用的报告。完整引擎结果和实际启动参数/环境保留一份；只有当同一段输出已完整存在于外部日志中时，才用文件引用代替 JSON 中的重复副本。后续退出判断补充的字段仍被合并保留。各 JSON 的归档引用规则由 `archive-info.json` 的 schema 2 标识，读取导出包的工具需跟随这些文件引用。

私有安装根目录规范化为 `$APP_FILES`、`$APP_CACHE`、`$NATIVE_LIB`，保留库名、档案相对路径及游戏/模组名称，以便继续定位。`REA_IME_TOKEN` 不进入 JSON 导出。驱动的 `library ... not found`、输入计数、内存原始样本和设备扩展信息仍有诊断价值，不因某一次错误发生在图形初始化就全部删除。导出不收集存档、游戏资源、音频、截图或整机 logcat，也不表示任意原始游戏文本均已做完整隐私脱敏。

旧归档中没有采集到的 Mesa/Vulkan 信息无法事后补回。新逻辑需要安装新版后产生新会话日志，才能验证设备上的实际采集效果。

## 本地主机验证

`SessionLogsTests` 覆盖归档刷新与会话隔离、工作线程最终退出证据、图形日志导出和截断、可选文件损坏、文件引用去重、退出证据合并、路径规范化、IME token 排除、历史 OS 退出过滤及运行时报告不被修改。这些检查不等于设备端 EGL、Vulkan 或真实画面验收。
