# 运行与指标口径

## 命令

| 命令 | 用途 |
| --- | --- |
| `record` | GET `/version`、`/connections`，采集 Mihomo 快照及健康状态 |
| `observe` | 持续读取明确选择的 DSHObserver/Codex 日志，可附加 Windows TCP 采样 |
| `sessions` | 列出会话、结束状态和事件数 |
| `export` | 一致性读事务导出指定会话 JSONL；文件只能新建 |
| `replay` | 从事件重建 Mihomo 连接状态，保留缺口和证据 ID |
| `diagnose` | 汇总应用指标和网络证据，默认 Markdown，`--json` 供 Codex/脚本读取 |
| `nodes soak` | 60 MB 持续下载及串行请求；候选默认预览，`--apply` 显式允许临时切换并恢复 |

所有命令用 `--help` 查看选项。没有安装常驻服务或自动启动任务。

节点浸泡测试的完整参数、输入 JSON、退出码和恢复限制见[浸泡测试说明](soak.md)。观察命令保持只读；`nodes soak` 是主动发流量的独立功能，只有 `--apply` 会写入控制器选择。

## Mihomo

只允许回环 IP 的 HTTP 根地址，不接受域名、用户信息、路径、查询参数或 fragment。请求禁用系统代理和重定向。默认每次成功采样后等待 1 秒，每个请求超时 3 秒；错误按 1/2/4/8/16/30 秒上限退避并带抖动，不追赶积压 tick。401/403 保存异常后停止重试并返回非零退出码。所有尝试都失败也返回非零。

控制器版本每轮读取。`connections: null` 和 `[]` 都是合法空列表；缺字段、重复 ID、错误字段类型不是空列表。首次快照只建立基线；超时/坏响应后产生缺口，恢复时切换 source epoch。单连接 start、地址或计数回退会新建连接键；总计数回退或版本变化会重建整个来源基线。同版本重启且计数没有可见回退时，API 不足以保证识别重启。

`record` 的有界队列默认容纳 8 个快照。队列满时累计丢弃数量，后续事件先记录缺口，再重建基线。SQLite 在专用线程写入，每个采样批次事务提交；失败停止采集。停止观察不会生成连接关闭事件。

本机 2026-10-30 的 `7897` 是 mixed-port，`7898` 是 SOCKS 入口，均不是 HTTP 控制器。候选 HTTP 控制器 `9097` 拒绝连接。运行时通过 Windows 命名管道暴露控制器；`cvctl doctor` 的自动检测引用旧管道名，显式指定实际 production 管道后返回 `Access is denied`。Wiesler 没有改配置、ACL 或启用管理员运行，也没有实现命名管道适配器。

## DSH

沿用已有 DSHObserver 0.1.2 的 schemaVersion 1 JSONL。默认目录为 `~/.dsh/state/dsh-observer/`，自定义 `DSH_HOME` 时应明确给出对应路径。Wiesler 不安装或修改运行中的 DSH 插件。

- `decode_tps = outputTokens × 1000 / decodeMs`，只对成功、usage 可用、输出 token 数和 decode 时长均为正的完成 attempt 计算。
- `end_to_end_tps` 使用本次 attempt 的 elapsedMs，包含首 token 前等待。
- 推理 token 已包含在 outputTokens 中，不相加。缺失 token 不以字符数或 chunk 数替代。
- TTFT 是首个有效内容增量到达客户端的等待，不是 TCP 首字节，也不是服务端纯解码时间。
- 分组维度为 provider/model/purpose/reasoning_effort；加权速度为有效样本 token 总数除以 decode 时长总数，保留失败和未结束请求。组内 TTFT 汇总包含所有提供 TTFT 的完成 attempt。
- `data-loss`、坏行、缺失来源、截断、停止时未读完的内容/半行会留下缺口。

## Codex

只读取显式选择的日志文件或目录。目录递归最多 6 层，只读取 `.log`/`.jsonl`，不扫描浏览器数据库或凭证文件。

已基于本机 2026-10-30 桌面日志核对：

- `orbit.messaging.stage` 中 `host.network`、`host.request`、`host.response_headers`：桌面 HTTP 阶段耗时或状态。
- `AppServerConnection response_routed`：内部 app-server RPC 耗时，单独分组。
- `sa_server_request_failed`：HTTP 错误状态和代码；`chatgpt_pubsub_transport_closed`：桌面 pubsub 传输事件，不等同模型流失败。
- 识别到的应用 `Transport error` / `stream disconnected` / `error sending request` 只保存错误类别，归属为未确认请求。
- 选中的 rollout JSONL 仅解析 `event_msg` 的 token_count、error 和任务边界；忽略 `response_item`，不读取内容来推算 token 速度。

不保存错误 message/stack、URL 查询、headers、prompt、正文或推理正文。只保留必要标识、白名单数值及分类。日志中的大写标识、端口和进程名不是认证凭证；日志中的任意正文不会进入数据库。

官方 [高级配置文档](https://learn.chatgpt.com/docs/config-file/config-advanced) 提供模型请求和流事件的 OpenTelemetry 指标说明；本轮未修改 Codex telemetry 配置、未接收 OTLP、未将桌面请求耗时冒充模型 TTFT。后续可在明确选择数据范围后加入该来源。

## TCP、代理和出口

`--codex-sockets` 使用固定的 Windows PowerShell 只读脚本，筛选 Codex 和 Mihomo/Clash 进程的 TCP 表，同时记录 PID 和可取得的进程启动时间。子进程隐藏窗口，有超时和输出大小上限。进程不可见、命令失败等记为采集缺口；不自动提权。

本轮宿主 PowerShell 能采到连接，但 Rust 内的子进程返回目标进程不可见，因此直接 TCP 采集尚未在此运行环境通过。已验证独立采样 JSON 的 `--socket-input` 导入路径；JSON 带原始 UTC 毫秒采样时间，报告不以导入时间冒充采样时间。采样脚本只应在已有正常权限的终端运行；不得为此降低执行策略或管道权限。

匹配同一 TCP 表中 Codex 客户端和代理服务端的反向四元组，才能报告确认的本机代理端口；未匹配的回环连接单独展示。代理进程的非回环连接是外连候选，不能自动归属某一模型请求。Mihomo 返回的链原样展示，不推断首尾方向；实际公网出口 IP 未主动查询，保持 null。

TCP 是采样证据，短连接可能错过。没有连接跟踪、抓包、PID 完整生命周期验证、TUN/NAT 映射或请求与连接 ID 的直接关系时，不建立请求到外连的确定关联。

## 存储、跟随与数据保留

数据库格式 v1 包含 sessions/events 及时间、来源、连接索引。连接状态和诊断从事件重建，尚未物化独立 associations/diagnoses 表。UTC 时间字段 `observed_at_utc` 为 Unix 毫秒，`monotonic_offset_ms` 为当前 Wiesler 会话内偏移；日志来源时间另存 `source_timestamp`。日志行带文件名、读入行序号和原文件 byte_offset；从文件末尾开始时，行序号是本次跟随的相对行号，byte_offset 仍对应源文件。

`observe` 每轮每文件最多读取 4 MiB，每行最多 1 MiB；新文件会被发现，半行保留到下一轮，文件长度回退/创建时间改变会换 epoch。不能保证识别同一文件就地改写且长度未回退的情况。退出时若仍有未读字节或半行，写缺口；重启从头读形成新 Wiesler 会话，诊断按来源记录身份去重。文件读取与本机 TCP 采样是串行的，直接 TCP 命令耗时会降低日志刷新频率；导入旧 TCP 快照不会产生新的网络观测。

默认总事件上限 1,000,000，可用 `--max-events` 调整。额度在写事务内核对，包括另一个写入会话；达到上限停止，不自动清理。上限按事件条数而非数据库字节数，尚未做大规模性能/磁盘容量调优。`record` 和 `observe` 可以向同库写独立会话，但报告不据此自动拼接请求。

正常结束状态为 completed/stopped，有缺口为 completed_with_gaps；存储失败尽力标记 incomplete。异常进程退出留下 recording_or_interrupted，表示活动或非正常结束，不能当作采集完整。未知 schema 或其他应用数据库拒绝写入，不自动迁移或重建。不存在旧版本迁移路径，因此本轮未声称完成迁移备份/恢复能力。
