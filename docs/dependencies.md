# 前置依赖准备记录

日期：2026-10-29。范围：Windows 本机开发环境与 P1 依赖准备。

2026-10-30：本轮实现没有修改 Cargo.toml 或 Cargo.lock，沿用这些锁定依赖。受控验收脚本使用现有 Python 标准库；可选 Windows TCP 探针使用已有 PowerShell/Get-NetTCPConnection，不安装系统组件。最新功能与验证边界见[本轮实现记录](implementation-20261030.md)。

2026-10-30 浸泡测试补充：`nodes soak` 使用 PATH 中已有的 curl 执行 HTTPS 数据面请求，控制器请求仍沿用只支持回环 HTTP 的 reqwest；没有增加或升级 Cargo 依赖、没有改 lockfile。本机已有 curl 8.21.0 / Schannel，未安装新组件。仅验证了本机模拟 HTTP 代理路径，真实 HTTPS 传输仍待实测，见[浸泡测试说明](soak.md)。

## 1. 环境基线

| 项目 | 检查结果 |
| --- | --- |
| rustc / cargo | 1.97.1 / 1.97.1 |
| 当前 toolchain | stable-x86_64-pc-windows-msvc |
| 工程 edition / rust-version | 2024 / 1.97；本次实际验证 1.97.1 |
| rustfmt / Clippy | 已安装 |
| C/C++ 工具链 | vswhere 确认 Visual Studio 2022 Build Tools，具备 VC x86/x64 工具组件 |
| Linux / Debian | 本次未在 Linux 环境构建或运行 |
| Npcap / libpcap / Tauri | 本阶段不需要，未安装或变更 |

Rust 工具链和 MSVC 均为已有环境，本次没有更改全局工具链或系统网络配置。普通终端 PATH 未直接找到 cl.exe，不代表组件缺失；以 Cargo 原生依赖构建结果验证其可发现性。

## 2. 首阶段依赖

添加依赖前逐个执行 `cargo info` 核对 crates.io 元数据与上游仓库，并查看本地下载的 Cargo.toml、相关 build.rs。以下版本为本次解析起点，精确的直接和传递依赖由 Cargo.lock 固定，后续构建使用 `--locked`。

| 包 | 本次版本 | 上游来源 | 选用方式与职责 |
| --- | --- | --- | --- |
| tokio | 1.54.1 | [tokio-rs/tokio](https://github.com/tokio-rs/tokio) | macros、rt-multi-thread、signal、sync、time；探针并发与退出协调，避免直接启用 full |
| reqwest | 0.13.5 | [seanmonstar/reqwest](https://github.com/seanmonstar/reqwest) | 关闭默认特性，仅 json；用于后续本机 HTTP 控制器 |
| serde | 1.0.229 | [serde-rs/serde](https://github.com/serde-rs/serde) | derive；事件与响应类型 |
| serde_json | 1.0.154 | [serde-rs/json](https://github.com/serde-rs/json) | JSON 快照和导出 |
| rusqlite | 0.41.1 | [rusqlite/rusqlite](https://github.com/rusqlite/rusqlite) | 关闭默认特性，启用 bundled；统一 SQLite 构建来源 |
| clap | 4.6.7 | [clap-rs/clap](https://github.com/clap-rs/clap) | derive；CLI 参数与帮助 |
| tracing | 0.1.44 | [tokio-rs/tracing](https://github.com/tokio-rs/tracing) | 结构化内部日志 |
| tracing-subscriber | 0.3.23 | [tokio-rs/tracing](https://github.com/tokio-rs/tracing) | env-filter、json；日志过滤与格式 |
| anyhow | 1.0.104 | [dtolnay/anyhow](https://github.com/dtolnay/anyhow) | CLI 错误传播；领域事件仍采用类型化错误 |

依赖已准备不代表对应功能已实现。当前入口只使用 clap 和 anyhow；其余依赖为紧接着的 P1 建设预备，并纳入编译验证。

### HTTP 与 TLS

当前 reqwest 不启用系统代理或 TLS 特性，准备范围仅为回环 HTTP 控制器。后续采集实现必须在配置入口验证地址并调用 `no_proxy()`、禁用重定向。不能允许远程明文携带控制器密钥。

对话中示例使用的 `rustls-tls` 特性不能直接沿用到本次 reqwest 版本：`cargo info reqwest` 显示 0.13.5 使用 `rustls` 等特性名。需要远程 HTTPS 时，再核实并加入相应 TLS 特性及其原生构建要求，保持证书验证。当前 CLI 没有发起网络请求。

### 构建行为

- Cargo 从 crates.io 下载归档、解析依赖并写入用户 Cargo 缓存，项目构建产物写入 `target/`。
- serde 的构建脚本生成私有模块并探测编译器；serde_json 选择平台浮点解析配置；anyhow 调用本地 rustc 做编译能力探测。
- `rusqlite` 的 Windows 后端为 `libsqlite3-sys 0.39.1`。已检查 bundled 路径：通过 cc 编译包内 SQLite C 源码，使用随包 bindings；不启用 buildtime_bindgen、SQLCipher 或扩展加载接口。
- 通过 Cargo metadata 枚举了传递依赖中的构建脚本来源，包含编译器探测、平台配置及数据生成；这不是完整供应链安全审计。
- 未执行安装文档中的远程 shell 脚本，未安装全局 Cargo 工具。依赖缓存下载完成后用 `--offline` 验证构建，无需在构建期间获取新包。

## 3. 后续阶段才引入的依赖

| 阶段 | 候选依赖或系统组件 | 引入前要完成的工作 |
| --- | --- | --- |
| WebSocket 快照 | tokio-tungstenite | 核实版本与特性，验证 Bearer header、重连及快照缺口语义 |
| 系统连接表 | Windows Rust bindings；Linux 后端库待选 | 对照系统 API 确认 IPv4/IPv6、TCP/UDP、进程权限与网络命名空间覆盖 |
| 抓包 | pcap、etherparse、Npcap/libpcap | 检查来源、原生安装行为和分发要求；验证回环、过滤与丢包统计 |
| GUI | Tauri 或本机 Web 技术栈 | 先验证核心 API；新增 npm 包前执行 npm view 并检查安装脚本 |

不将这些候选包提前加入默认编译。数据库采用 bundled SQLite，因此 P1 不要求另装 SQLite 服务或 CLI；Windows 需要 MSVC 和 Windows SDK，Debian 需 C 编译工具链。后者仍待实际机器验证。

## 4. 开发命令

第一次允许联网下载依赖，之后可以离线重现锁定版本：

```sh
cargo fetch --locked
cargo build --locked --offline
cargo fmt --all -- --check
cargo clippy --locked --offline --all-targets -- -D warnings
cargo run --locked --offline -- --help
cargo run --locked --offline -- --version
```

当前无业务测试，也没有数据库读写或真实 Mihomo 联调。P1 开始后按实现报告补充状态机、存储和受控网络测试，不以空测试套件作为功能验证。

## 5. 本次验证结果

依赖解析与下载已成功，Cargo.lock 固定 127 个外部包。以下检查在 Windows MSVC / Rust 1.97.1 上完成：

| 检查 | 结果 |
| --- | --- |
| `cargo generate-lockfile`、`cargo fetch --locked` | 成功，生成锁文件并下载依赖 |
| `cargo metadata --locked --offline --format-version 1` | 成功，检查依赖来源和构建脚本清单 |
| `cargo build --locked --offline` | 通过，包括 bundled SQLite 原生编译 |
| `cargo fmt --all -- --check` | 通过 |
| `cargo clippy --locked --offline --all-targets -- -D warnings` | 通过，无警告 |
| `cargo run --locked --offline -- --help` / `--version` | 通过，显示开发骨架说明和 `0.1.0-dev` |
| 直接运行 CLI，无参数 | 正常显示帮助，未启动采集 |
| Markdown 本地链接、代码围栏与 Git 空白检查 | 通过 |

已知限制：尚未验证 Linux、TLS、代理 API、SQLite 运行时读写、系统连接表或抓包；这些都不是当前骨架的完成能力。
