# 2026-10-30 实现记录

分支：`codex/implementation`。基线：`8eebd7d`。本轮实现保留在工作区，未提交或推送。Cargo.toml / Cargo.lock 未改变，没有新增依赖。

## 已实现

- P1：回环 HTTP Mihomo GET 采集、严格快照解析、版本化事件、连接差分与缺口恢复、限长响应、退避、鉴权终止、有界队列、SQLite 单写入线程和批次事务、会话隔离、JSONL 导出和事件回放。
- 按用户追加需求接入 DSHObserver 的 JSONL，移植 usage/TPS 口径，并记录 Apache-2.0 来源。成功和失败、会话与辅助请求分开统计。
- Codex 桌面日志/选定 rollout 事件的白名单解析，区分桌面 HTTP、内部 RPC、pubsub 和未归属的应用传输错误。
- `observe` 持续跟随文件，记录来源缺失/截断/半行/未读积压；`diagnose` 输出 Markdown 和 JSON，供用户与 Codex 读取。
- Windows TCP 采样脚本和快照导入、Codex 到代理进程反向四元组关联、代理外连候选。实际公网出口 IP 与请求级出口保持未知。

## 已有证据

最终检查：`cargo test --locked --offline` 的 29 项测试全部通过，`cargo build --locked --offline`、`cargo fmt --all -- --check`、`cargo clippy --locked --offline --all-targets -- -D warnings` 和 `git diff --check` 通过。部分本机增量编译提示硬链接不可用而改用复制，这是构建缓存提示，不是业务验证结果。

- Windows / Rust 1.97.1，锁定依赖离线构建。
- 自动测试覆盖状态机、解析/隐私过滤、事务回滚、未知 schema 保留、事件额度跨写入会话、导出引用、恢复投影、DSH 指标、Codex 范围隔离、日志跟随/截断、端口关联。
- `scripts/verify_p1.py` 的 7 组受控 CLI 验收覆盖 HTTP→SQLite→JSONL→回放、重启、401、存储上限、强制终止后的可读性、写锁造成的队列丢弃和远程地址拒绝。所有请求来自模拟控制器，不是实机 Mihomo 验收。
- 实际导入 2026-10-28 DSHObserver 文件：9 个完成 attempt，8 个有效 TPS 样本；conversation 的 3 个样本加权 23.51 token/s，unclassified 的 5 个有效样本加权 18.01 token/s。这是该历史文件的结果，不能代表当前实时速度或模型一般性能。
- 实际导入 2026-10-30 Codex 桌面日志，保留 HTTP 阶段耗时、内部 RPC 与错误类别。日志没有提供可据以计算 Codex 模型流 TTFT/TPS 的完整证据。
- 经正常权限申请的宿主 PowerShell 采到 TCP 表；导入后反向四元组匹配确认本次快照的 Codex 代理端口为 7897。原始采样时间保留，未主动探测公网出口。

本机数据在被 Git 忽略的 `data/`；测试数据在 `target/`。不把本机进程、目标地址和会话标识提交进仓库。

本轮可查阅的本机产物：`data/diagnostic-20261030.md` / `.json`，来自 `data/local-observation-20261030-02.sqlite3` 的 2,209 个事件；TCP 快照为 `data/windows-tcp-20261030-01.json`。最终模拟验收记录为 `target/p1-acceptance/1793331395496104500/summary.json`。这些路径是当前工作区证据，不随源码提交。

## 未完成或未验证

- 当前真实 Mihomo 仅有命名管道，`cvctl` 对实际管道返回 OS Access is denied；HTTP 9097 不监听。未改代理配置、管道 ACL、执行策略或管理员身份。真实 Mihomo 生命周期/断流验收未完成。19:00 后续更新：已把 `enable_external_controller` 置为 `true` 并重启客户端，9097 可用，HTTP 控制器的读写均已在本机验证，见[节点选择实测](node-selection-20261030.md)；生命周期/断流验收仍未完成。
- Rust 子进程内的 Windows TCP 采样在此宿主环境看不到目标进程，已作为 SourceGap；独立 PowerShell 采样与快照导入已验证。不能声称直接持续 TCP 采集已验收。
- 没有实时模型调用、生产断流重现、Codex OTLP 接入、请求到代理节点的精确映射、出口公网 IP 验证、TCP FIN/RST 抓包、Linux/Debian 实测或 GUI。
- P1 的源码与隔离验证完成，但报告原定的真实控制器验收仍待可访问来源；P2 仅完成应用日志及 Windows TCP 证据的这一部分。
