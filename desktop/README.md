# Wiesler 桌面前后端集成

桌面通过 Tauri IPC 调用仓库的 `wiesler` Rust 库；不需要额外 Web 服务或 npm 依赖。打开 `ui/index.html` 仅预览界面，真实数据需要启动桌面程序。

## 使用

1. 在控制器地址栏输入 Mihomo HTTP External Controller 的回环 IP 根地址，点击「连接 / 重新连接」。默认 `http://127.0.0.1:9090` 是待填写的起点，不会自动连接，也不是 mixed-proxy 端口。
2. 如需鉴权，在启动桌面程序前通过现有运行环境提供 `WIESLER_MIHOMO_SECRET`。密钥不经过页面、命令参数或日志。
3. 查看连接、PID、目标、规则、原始代理链及累计流量；可搜索、筛选协议、查看详情和暂停。请求完成后间隔 1 秒再次采样，失败自动重试。
4. 在「诊断中心」填写 CLI 已生成的 SQLite 文件路径，读取会话并选择一项生成报告。读取使用原有只读数据库接口，报告与 CLI 共用诊断实现。

下载/上传速率来自连续有效快照计数之差，首次、失败恢复和计数回退显示「等待差分」。失败时清空当前连接与速率，累计量和采样时间保留最后成功值；暂停保留最后快照。趋势展示最近 60 次采样，缺口不连线。实时观察不写入数据库，需要持久化时仍使用 CLI `record` / `observe`。

## 验证

```powershell
node --test desktop/tests/ui.test.cjs
cargo test --manifest-path desktop/Cargo.toml --locked --offline --target-dir "$env:LOCALAPPDATA/Wiesler/build"
cargo build --manifest-path desktop/Cargo.toml --locked --offline --target-dir "$env:LOCALAPPDATA/Wiesler/build"
```

验证包含后端 40 项回归测试、2 项桌面命令集成测试和 6 项前端状态测试。2026-10-30 后续已接入本机真实 Mihomo v1.19.32（9097），在 WebView2 窗口中完成实时连接、速率、搜索、协议筛选、详情、暂停恢复、控制器失败恢复与 SQLite 诊断检查；真实会话采集 20 次快照、2618 条事件。发现并修复暂停后空结果文案错误和趋势图最新点对齐错误，详见[实机验收记录](../docs/desktop-acceptance-20261030.md)。没有修改代理配置、启动测速或切换节点；长时间运行和业务断流根因不属于这次短时验收结论。

新增依赖仅为本仓库 path 库和已使用的 serde；继承后端的锁定依赖与 SQLite bundled 构建行为。桌面 lockfile 增补后端依赖闭包，没有升级根项目 lockfile。接口用法核对了 [Tauri 官方 GitHub](https://github.com/tauri-apps/tauri) 与 [官方命令文档](https://v2.tauri.app/develop/calling-rust/)，未复制第三方实现代码。

界面为原创实现，框架采用官方 Tauri（https://github.com/tauri-apps/tauri，MIT / Apache-2.0）；未复制第三方界面代码。Tauri 的构建脚本生成平台资源并编译 Windows WebView2 桌面运行时适配。

启动日志优先追加到 EXE 同目录的 wiesler-startup.log，写入失败依次尝试 LOCALAPPDATA/Wiesler/desktop.log 和临时目录。记录 PID、时间、目录创建、WebView2 创建错误及 panic 堆栈。弹窗显示实际日志路径；所有候选路径均失败时显示写入失败原因。

### Windows 受限构建环境

2026-10-30 排查发现仓库内构建产物继承 Low Mandatory Level 标签，用户启动后也是低完整性进程，因此创建普通 AppData/WebView2 目录时报 os error 5。自签名不改变完整性级别。通过正式权限流程，在正常用户目录使用独立 target-dir 构建，不修改现有 ACL 或完整性标签：

```powershell
cargo build --manifest-path desktop/Cargo.toml --locked --offline --target-dir "$env:LOCALAPPDATA/Wiesler/build"
```

本机交付入口：`%LOCALAPPDATA%/Wiesler/build/debug/wiesler-desktop.exe`。重建后须重新自签名。旧 target、target-verified、target-logged 目录保留作诊断证据，不再作为推荐启动入口。
