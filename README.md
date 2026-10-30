# Wiesler

Rust 编写的本地网络与代理可观测性工具，面向客户端断流、代理链路异常和连接归属排查。

已实现 Mihomo HTTP 采样、快照差分、SQLite 证据日志、JSONL 导出/回放，以及 DSHObserver 和 Codex 日志接入。`diagnose` 提供 Markdown / JSON 诊断，包含 DSH TPS、Codex 分范围耗时/错误、TCP 代理端口和出口候选。

当前为 Windows 上验证的开发版本。真实 Mihomo 控制器联调受本机命名管道权限限制；不把隔离测试视为真实代理验收，也不从连接消失推断请求失败。

## 开发入口

使用 Rust 1.97 或更高版本；本次验证版本为 1.97.1。Windows 使用 MSVC Build Tools，Linux 需要 C 编译工具链，供 bundled SQLite 构建使用。

```sh
cargo fetch --locked
cargo build --locked
cargo run --locked -- --help
cargo run --locked -- --version
```

## 采集与诊断

```powershell
# Mihomo 必须是 HTTP External Controller，不是 mixed-port。
# 密钥仅从已有 WIESLER_MIHOMO_SECRET 环境变量读取。
.\target\debug\wiesler.exe record --controller http://127.0.0.1:9090 --db wiesler.sqlite3

# DSH 使用现有 DSHObserver 插件日志；从开头读取后持续跟随。
.\target\debug\wiesler.exe observe --db wiesler.sqlite3 --dsh "$HOME\.dsh\state\dsh-observer"

# Codex 日志路径由使用者明确选择，可同时选 DSH、日志和 TCP。
.\target\debug\wiesler.exe observe --db wiesler.sqlite3 --codex-log 'C:\path\to\Codex\Logs' --codex-sockets

.\target\debug\wiesler.exe diagnose --db wiesler.sqlite3
.\target\debug\wiesler.exe diagnose --db wiesler.sqlite3 --json
.\target\debug\wiesler.exe sessions --db wiesler.sqlite3
.\target\debug\wiesler.exe export --db wiesler.sqlite3 --session '<session-id>' --output events.jsonl
.\target\debug\wiesler.exe replay --db wiesler.sqlite3 --session '<session-id>'
```

`record --samples N` 有限次采样；`observe --polls 1` 做一轮导入，`--from-end` 只跟随已存在文件的新内容。默认运行到 Ctrl+C；停止时保存待处理事件。`--socket-input snapshot.json` 可导入 [TCP 采样脚本](scripts/observe-sockets.ps1) 产生的带时间快照。

详细口径、本机结果与限制见 [运行说明](docs/running.md) 和 [本轮实现记录](docs/implementation-20261030.md)。DSH 计算口径的复用与许可证见 [来源声明](THIRD_PARTY_NOTICES.md)。

## 验证

```powershell
cargo test --locked --offline
cargo fmt --all -- --check
cargo clippy --locked --offline --all-targets -- -D warnings
python scripts/verify_p1.py
```

HTTP 测试只访问随机回环端口的模拟控制器；受限环境需要正常的本机网络权限。验收脚本会新建 `target/p1-acceptance/` 测试数据，涵盖故障恢复、导出、回放、队列溢出和测试进程异常退出，不改变真实代理。

## 文档

- [实现报告](docs/implementation-report.md)：目标、交付范围、阶段与验收。
- [架构规划](docs/architecture.md)：模块边界、事件模型、连接状态、关联与存储。
- [前置依赖准备](docs/dependencies.md)：依赖来源、构建行为、环境要求和验证记录。

默认方向为本机只读观察，先接入 Mihomo，再扩展系统连接表和按需抓包；Web/Tauri 界面放在后续阶段。
