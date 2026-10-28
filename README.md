# Wiesler

Rust 编写的本地网络与代理可观测性工具，面向客户端断流、代理链路异常和连接归属排查。

当前处于架构与依赖准备阶段，CLI 仅提供帮助和版本信息，尚未实现采集或诊断。

## 开发入口

使用 Rust 1.97 或更高版本；本次验证版本为 1.97.1。Windows 使用 MSVC Build Tools，Linux 需要 C 编译工具链，供 bundled SQLite 构建使用。

```sh
cargo fetch --locked
cargo build --locked
cargo run --locked -- --help
cargo run --locked -- --version
```

## 文档

- [实现报告](docs/implementation-report.md)：目标、交付范围、阶段与验收。
- [架构规划](docs/architecture.md)：模块边界、事件模型、连接状态、关联与存储。
- [前置依赖准备](docs/dependencies.md)：依赖来源、构建行为、环境要求和验证记录。

默认方向为本机只读观察，先接入 Mihomo，再扩展系统连接表和按需抓包；Web/Tauri 界面放在后续阶段。
