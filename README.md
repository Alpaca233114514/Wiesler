# Wiesler

帮你看清电脑和手机上哪些程序在联网、流量走了哪条代理线路、有没有断流或丢包，以及哪个代理节点更稳定的本地排查工具。

提供 **桌面端**、**Android 客户端** 与 **命令行工具（CLI）** 三种形态，核心采用 Rust 编写，主要支持 Windows 与 Android 11+，也支持在 Linux 上从源码编译 CLI。

## 包含组件

| 组件 | 平台 / 技术栈 | 主要用途 |
| --- | --- | --- |
| **桌面端** | Windows（Tauri / WebView2） | 实时连接图谱、程序与连接对应关系、系统/代理公网出口对比、ICMP 丢包监测、本地 DNS 与隐私检查、历史诊断，见 [desktop/README.md](desktop/README.md) |
| **Android 客户端** | Android 11+（Kotlin 原生） | 无需额外 VPN 查看手机总流量与海外代理流量、应用流量排行、可选接入手机 Mihomo 查看连接与网络诊断，见 [android/README.md](android/README.md) |
| **CLI 工具** | Rust 命令行 | 记录 Mihomo 连接历史到 SQLite 并导出/回放，结合 DSH / Codex 日志与本机 TCP 状态生成诊断报告，以及批量测试代理节点稳定性 |

## 核心功能

- **实时连接与历史回放**：连接本机 Mihomo HTTP 控制器，把连接变化记录到本地 SQLite，方便事后用 `sessions`、`export` 和 `replay` 按时间复盘。
- **日志与连接联合诊断**：把 DSHObserver 日志、指定的 Codex 日志和本机 TCP 连接放在一起分析，一键输出 Markdown 或 JSON 诊断报告，查看生成速度、各阶段耗时、报错原因和实际经过的代理端口。
- **节点稳定性测试**：`nodes soak` 通过一次 60 MB 持续下载 + 15 次连续请求评估节点稳不稳，输出成功数、失败原因、中位数 / p90 延迟和吞吐速度。默认先预览计划，加上 `--apply` 才会临时切节点测速，结束后自动切回原节点。
- **可视化桌面与移动端**：桌面端提供连接图谱、公网出口 IP 对比、ICMP 丢包监测和本地 DNS / 隐私检查；手机端支持在不替换现有代理的情况下监控流量和排查网络。

## 快速开始

### 下载预编译版本

可以直接前往 [GitHub Releases](https://github.com/Alpaca233114514/Wiesler/releases) 下载 Windows 便携版或 Android APK。

### 从源码构建 CLI

环境要求：
- Rust **1.97+**
- **Windows**：MSVC Build Tools（用于编译内置 SQLite）
- **Linux**：基础 C 编译工具链

```sh
cargo build --locked
cargo run --locked -- --help
```

桌面端和 Android 端的构建步骤见 [desktop/README.md](desktop/README.md) 和 [android/README.md](android/README.md)。

## CLI 常用命令

### 1. 记录连接与生成诊断报告

如果 Mihomo 控制器设置了密钥，先通过环境变量 `WIESLER_MIHOMO_SECRET` 传入。

```powershell
# 记录 Mihomo 实时连接（填写 HTTP External Controller 地址，不是代理端口）
.\target\debug\wiesler.exe record --controller http://127.0.0.1:9090 --db wiesler.sqlite3

# 导入并持续跟踪 DSHObserver 日志
.\target\debug\wiesler.exe observe --db wiesler.sqlite3 --dsh "$HOME\.dsh\state\dsh-observer"

# 导入指定的 Codex 日志，并附带采样本机 TCP 连接
.\target\debug\wiesler.exe observe --db wiesler.sqlite3 --codex-log 'C:\path\to\Codex\Logs' --codex-sockets

# 输出诊断报告（Markdown 或 JSON）
.\target\debug\wiesler.exe diagnose --db wiesler.sqlite3
.\target\debug\wiesler.exe diagnose --db wiesler.sqlite3 --json

# 查看会话列表、导出 JSONL 或回放单次会话
.\target\debug\wiesler.exe sessions --db wiesler.sqlite3
.\target\debug\wiesler.exe export --db wiesler.sqlite3 --session '<session-id>' --output events.jsonl
.\target\debug\wiesler.exe replay --db wiesler.sqlite3 --session '<session-id>'
```

常用选项：
- `record --samples N`：采样 N 次后自动退出（默认一直运行到按 `Ctrl+C`）。
- `observe --polls 1`：只导入一轮现有日志就退出；加上 `--from-end` 可只跟踪新增内容。
- `observe --socket-input snapshot.json`：导入由 [scripts/observe-sockets.ps1](scripts/observe-sockets.ps1) 生成的 TCP 快照。

### 2. 测试代理节点稳定性

需要系统 `PATH` 中有 `curl`。

```powershell
# 先预览测试计划（不切节点、不跑流量）
.\target\debug\wiesler.exe nodes soak --proxy http://127.0.0.1:7897 --group '🔰 节点选择' --node '🇨🇦 加拿大' --out soak-plan.json

# 确认无误后加 --apply 开始测试，结束或按 Ctrl+C 后自动切回原节点
.\target\debug\wiesler.exe nodes soak --proxy http://127.0.0.1:7897 --group '🔰 节点选择' --node '🇨🇦 加拿大' --apply --out soak-result.json
```

- 也可以用 `--from stable.json --top 5` 按历史结果挑前 5 个节点批量测；如果不传 `--from` 和 `--node`，则直接测当前线路。
- 提示：加 `--apply` 切换代理组时，同一代理组下的其他流量也会随之切换。

## 开发与测试

```powershell
cargo test --locked
cargo fmt --all -- --check
cargo clippy --locked --all-targets -- -D warnings
python scripts/verify_p1.py
```

## 更多文档

- [运行与命令详解](docs/running.md)：CLI 参数说明、指标计算方式与数据库结构
- [节点稳定性测试说明](docs/soak.md)：`nodes soak` 完整参数、输出格式与节点恢复机制
- [隐私与本地 DNS 检查](docs/privacy-inspector.md)：桌面端隐私检查项、DNS 捕获与报告说明
- [架构设计](docs/architecture.md)：整体模块划分、事件模型与存储设计
- [打包与发布](docs/releasing.md)：Windows 便携版和 Android APK 的构建与签名流程
- [依赖说明](docs/dependencies.md)：第三方依赖清单与构建环境要求

## 许可证

本项目基于 [Apache-2.0](LICENSE) 协议开源；第三方组件与参考来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
