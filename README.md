# Wiesler

基于 Rust 的本地网络与代理可观测性工具，面向客户端断流、代理链路异常和连接归属排查，包含 **CLI**、**桌面工作台** 与 **Android 客户端**。

> [!NOTE]
> 当前以 Windows 为主要开发与验证环境（Rust 1.97+）。真实 Mihomo 长生命周期断流与 `nodes soak` 的全链路实机验收尚未全部完成；隔离或模拟测试不替代真实代理验收，也不从连接消失直接推断请求失败。

## 组件概览

| 组件 | 形态 | 说明与入口 |
| --- | --- | --- |
| **CLI 核心** | Rust 命令行 | Mihomo HTTP 采样、快照差分、SQLite 证据日志、JSONL 导出/回放、DSH/Codex 日志接入、诊断与节点浸泡测试 |
| **桌面工作台** | Tauri / WebView2 | 实时连接图谱、系统 TCP 归属、公网出口探测、ICMP 丢包监测、本地 DNS/隐私检查与历史诊断，见 [桌面使用说明](desktop/README.md) |
| **Android 客户端** | Kotlin 原生（Android 11+） | 手机实时流量与海外转发双监控、应用排行、可选 Mihomo 连接图谱与网络诊断，见 [移动端说明](android/README.md) |

## 核心能力

- **只读采样与回放**：连接本机 Mihomo HTTP External Controller，执行快照差分、缺口记录与 SQLite 持久化，支持 `sessions`、`export` 和 `replay` 事件级回放。
- **跨层诊断**：接入 DSHObserver JSONL 与显式选定的 Codex 日志，结合 Windows TCP 连接快照生成 `diagnose`（Markdown / JSON），输出 DSH TPS、Codex 分阶段耗时/错误、确认代理端口及外连候选。
- **节点浸泡测试**：`nodes soak` 通过持续下载与串行请求测试节点稳定性，默认只预览，显式 `--apply` 后临时切换并在结束或 `Ctrl+C` 时尝试恢复原选择。
- **桌面工作台与隐私检查**：在默认只读观察之上提供可视化图谱、出口对比、本地 DNS 事件与网卡报文检查，详情见 [桌面使用说明](desktop/README.md) 与 [隐私检查说明](docs/privacy-inspector.md)。

## 快速开始

### 环境要求

- Rust **1.97** 或更高版本（已验证版本：`1.97.1`）
- **Windows**：MSVC Build Tools（供 `rusqlite` bundled SQLite 构建）
- **Linux**：C 编译工具链（设计支持，尚未完成实机验收）

### 构建与检查

```sh
cargo fetch --locked
cargo build --locked
cargo run --locked -- --help
cargo run --locked -- --version
```

桌面端与 Android 端的独立构建说明见 [桌面使用说明](desktop/README.md) 和 [移动端说明](android/README.md)。

## CLI 使用指南

### 采集、观察与诊断

```powershell
# 1. 采集 Mihomo 快照（仅支持 HTTP External Controller，不是 mixed-port）
#    控制器密钥仅从已有 WIESLER_MIHOMO_SECRET 环境变量读取
.\target\debug\wiesler.exe record --controller http://127.0.0.1:9090 --db wiesler.sqlite3

# 2. 接入 DSHObserver 日志（从开头读取后持续跟随）
.\target\debug\wiesler.exe observe --db wiesler.sqlite3 --dsh "$HOME\.dsh\state\dsh-observer"

# 3. 接入显式选定的 Codex 日志，可同时启用本机 TCP 采样
.\target\debug\wiesler.exe observe --db wiesler.sqlite3 --codex-log 'C:\path\to\Codex\Logs' --codex-sockets

# 4. 生成诊断报告与会话导出/回放
.\target\debug\wiesler.exe diagnose --db wiesler.sqlite3
.\target\debug\wiesler.exe diagnose --db wiesler.sqlite3 --json
.\target\debug\wiesler.exe sessions --db wiesler.sqlite3
.\target\debug\wiesler.exe export --db wiesler.sqlite3 --session '<session-id>' --output events.jsonl
.\target\debug\wiesler.exe replay --db wiesler.sqlite3 --session '<session-id>'
```

常用参数说明：

- `record --samples N`：有限次采样后退出；默认持续运行到 `Ctrl+C`，停止时保存待处理事件。
- `observe --polls 1`：执行一轮导入后退出；`--from-end` 仅跟随已存在文件的新增内容。
- `--socket-input snapshot.json`：导入由 [TCP 采样脚本](scripts/observe-sockets.ps1) 生成的带时间快照。

采集口径、本机实测限制见 [运行与指标口径](docs/running.md) 与 [本轮实现记录](docs/implementation-20261030.md)；DSH 计算口径及第三方代码来源见 [来源声明](THIRD_PARTY_NOTICES.md)。

### 节点浸泡测试

`nodes soak` 默认执行 **1 次 60 MB 持续下载 + 15 次串行请求**，输出逐次样本、失败分类、中位数、`p90` 与吞吐（依赖 `PATH` 中已有 `curl`）。

```powershell
# 仅预览候选计划，不产生测速流量；控制器密钥沿用 WIESLER_MIHOMO_SECRET
.\target\debug\wiesler.exe nodes soak --proxy http://127.0.0.1:7897 --group '🔰 节点选择' --node '🇨🇦 加拿大' --out soak-plan.json

# 显式允许临时切换；正常结束、失败或 Ctrl+C 后尝试恢复原选择
.\target\debug\wiesler.exe nodes soak --proxy http://127.0.0.1:7897 --group '🔰 节点选择' --node '🇨🇦 加拿大' --apply --out soak-result.json
```

- 支持 `--from stable.json --top 5` 按历史稳定性筛选候选；省略 `--from` 与 `--node` 时将测试当前路由且不切换节点。
- 切换代理组会影响该组承载的其他流量，运行前请核实目标路由。完整参数、恢复边界与隔离验证见 [节点浸泡测试](docs/soak.md)。

## 验证与测试

```powershell
cargo test --locked --offline
cargo fmt --all -- --check
cargo clippy --locked --offline --all-targets -- -D warnings
python scripts/verify_p1.py
```

- 单元测试中的 HTTP 用例仅访问随机回环端口的模拟控制器，在受限环境运行需保留本机回环网络权限。
- `scripts/verify_p1.py` 会在 `target/p1-acceptance/` 下生成测试数据，覆盖故障恢复、导出、回放、队列溢出与进程异常退出，不修改真实代理配置。

## 文档索引

- [运行与指标口径](docs/running.md)：CLI 命令、Mihomo / DSH / Codex / TCP 口径与存储约束
- [架构规划](docs/architecture.md)：模块边界、事件模型、连接状态、关联与存储设计
- [实现报告](docs/implementation-report.md) · [2026-10-30 实现记录](docs/implementation-20261030.md)：目标、交付范围、阶段与实机证据边界
- [前置依赖准备](docs/dependencies.md)：依赖来源、构建行为、环境要求与验证记录
- [节点选择实测](docs/node-selection-20261030.md) · [节点浸泡测试](docs/soak.md)：四阶段筛选方法、度量口径与切换恢复机制
- [隐私检查说明](docs/privacy-inspector.md) · [本地 DNS 检查说明](docs/dns-local-capture-20261103.md)：工作台被动观察、DNS 事件与 PktMon 捕获边界
- [打包与发布](docs/releasing.md)：Windows 便携版与 Android 发布签名流程

## 许可证

本项目基于 [Apache-2.0](LICENSE) 开源；第三方实现与参考说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
