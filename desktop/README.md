# Wiesler Desktop

桌面默认进入实时工作台，自动采集系统 TCP 连接，并扫描、验证本机 Mihomo 控制器。唯一可用控制器自动连接；多个结果在「设置」选择。图谱、运行监测、历史诊断和设置在同一页面切换，保留采集和检查状态。Mihomo 图谱、流量概览、趋势及请求日志共享同一次控制器快照；ICMP 和 SQLite 诊断调用已有 Rust 命令。演示数据需手动选择。详见[融合联调记录](../docs/desktop-integration-20261102.md)与[图谱交互说明](../docs/neuron-demo-20261102.md)。

桌面通过 Tauri IPC 调用仓库的 `wiesler` Rust 库；不需要额外 Web 服务或 npm 依赖。打开 `ui/studio.html` 仅预览界面，真实数据需要启动桌面程序。

## 使用

1. 启动后自动观察本机已建立 TCP 连接，并枚举本机监听端口。只有 `/version` 和 `/connections` 都通过验证的服务才可自动连接；HTTP/SOCKS 代理端口不会被当成控制器。「设置」提供重新扫描、多控制器选择和高级手动地址。
2. 本机 Clash Verge Rev 的当前运行配置可提供控制器地址及对应密钥。后端仅将配置密钥用于该回环端点；扫描未知服务不携带密钥。已有 `WIESLER_MIHOMO_SECRET` 可显式覆盖配置密钥，密钥不经过页面、命令参数或日志。
3. 在「连接图谱」查看连接、PID、目标、规则、原始代理链及累计流量；支持搜索、协议/来源筛选、检查器和快照回放。顶栏和导航固定，竖条属于导航下方的内容区；检查器和会话表保留内部滚动，图谱面板尺寸不压缩。同域名不同端口叠成一个图谱节点，检查器可切换具体连接；会话表保留逐连接记录，采用应用、目标、流量、速率趋势四列；协议和端口显示在目标下方。检查器保留细线节点路径。「冻结」仅冻结图谱，「暂停采集」停止系统和控制器采样。控制器请求完成后间隔 1 秒采样，系统连接请求完成后间隔 3 秒采样，失败自动重试。切换工作台视图不重启采样，修改地址须点击重连才生效。
4. 在「历史诊断」填写 CLI 已生成的 SQLite 文件路径，读取会话并选择一项生成报告。读取使用原有只读数据库接口，报告与 CLI 共用诊断实现。

下载/上传速率来自连续有效快照计数之差，首次、失败恢复、暂停恢复、超过 5 秒的缺口和计数回退显示「等待差分」。控制器失败时清空其连接、速率和累计量，系统连接继续独立显示；图谱若已冻结或回放则保留对应历史证据，顶部仍显示最新采集错误。暂停采集保留最后快照。窗口隐藏暂停系统和控制器采样，重新显示后自动恢复此前开启的采样。趋势展示最近 60 次采样，缺口不连线。实时观察不写入数据库，需要持久化时仍使用 CLI `record` / `observe`。

系统连接表提供连接端点、进程和状态；进程名缺失时优先按 Windows PID 表补全，再用 TCP 连接或 UDP 本地端点的唯一所属进程补全。无可靠归属时保留 PID/未识别原因，不误归给其他应用。系统来源当前不提供逐连接流量计数，也不能单独证明是否经过 VPN。来源筛选区分 Mihomo 明确报告的 DIRECT 直连、代理转发以及路由待确认的系统连接；只有唯一匹配的源端点、目标/回环端点与 PID 证据才合并两侧记录。

控制器请求日志保留本次打开期间最近 100 条结果，显示时间、HTTP 状态码和错误信息；200 等成功响应显示绿色，401 / 403 等失败显示红色，超时和网络错误没有状态码时明确标注。恢复采样后仍保留之前的错误。诊断报告新增 DSH / Codex 最近日志状态表，展示来源已有的成功与失败状态码、错误码及来源行号。

这些状态码属于控制器请求或应用日志记录的请求；Mihomo `/connections` 不提供各网站的 HTTP 响应码，不将代理连接成功推断为 HTTP 200。日志不记录响应正文、请求头或密钥。

## 公网出口地址

工作台显示系统出口和代理出口 IP、国家/地区、城市、运营商，以及各自探测时间；自动连接后探测一次，「刷新出口」重新探测。系统路径显式不使用 HTTP/SOCKS 代理，仍按操作系统路由运行，可能经过 TUN/VPN；代理路径从已连接控制器的 `/configs` 取得 HTTP/Mixed 端口，经过 Mihomo 当前路由，不修改代理组或全局模式。两次结果可能相同，也不代表每条业务连接都采用同一出口。

使用已有系统 curl，通过 HTTPS 请求 [ipify 的公开 IP 接口](https://www.ipify.org/)。首选 `api64.ipify.org`，失败时仅尝试一次 `api.ipify.org`；每次连接超时 5 秒、请求上限 10 秒。不读取 curlrc、不继承代理环境、不跳过 TLS 验证，不向查询服务发送控制器密钥。有效 IP 再通过 HTTPS 查询 [IPWhois 地址库](https://ipwhois.io/documentation)；仅接受与实测 IP 一致的返回数据，地理查询失败时保留有效 IP 并显示位置未知。无有效 IP 时显示错误，清空过期值；查询来源可悬停查看。

## ICMP 丢包率监控

界面「ICMP 丢包率」卡片填写单播 IPv4 地址（默认 `1.1.1.1`），点击「开始监控」。无需连接 Mihomo。Windows 使用系统 `IcmpSendEcho`，每次发送 32 字节载荷，等待回包最多 2 秒，完成后间隔 1 秒继续；可随时停止。窗口隐藏时自动停止，再次开始建立新窗口，不计入隐藏期间的时间。

显示最近 60 次已完成探测的发送、收到、未回包、探测错误数量和成功回包平均 RTT。丢包率 = 未回包 /（收到 + 未回包）；没有有效探测时显示 `—`。权限、路由或系统执行错误单列并排除分母；60 次是样本窗口，不是固定一分钟。停止保留最后结果，再次开始重置；本轮样本仅保存在内存，不写入 SQLite。

ICMP 按系统路由发出，HTTP / SOCKS 代理不能保证承载它；目标禁 Ping、限速或路径过滤都可能造成未回包，不能据此认定业务或 WireGuard 隧道实际丢包率。当前支持 IPv4，未加入域名解析和 IPv6 探测。

实现前检查了 GitHub 上的 ICMP 监测项目，未复制第三方代码或新增依赖。接口依据 [Microsoft IcmpSendEcho 文档](https://learn.microsoft.com/en-us/windows/win32/api/icmpapi/nf-icmpapi-icmpsendecho)。

## 验证

```powershell
node --test --test-isolation=none desktop/tests/ui.test.cjs desktop/tests/studio.test.cjs
cargo test --manifest-path desktop/Cargo.toml --locked --offline --target-dir "$env:LOCALAPPDATA/Wiesler/build"
cargo build --manifest-path desktop/Cargo.toml --locked --offline --target-dir "$env:LOCALAPPDATA/Wiesler/build"
```

以下为 2026-10-30 的历史验收；当前版本验证见[融合联调记录](../docs/desktop-integration-20261102.md)。历史验证包含后端 40 项回归测试、2 项桌面命令集成测试和 6 项前端状态测试。2026-10-30 后续已接入本机真实 Mihomo v1.19.32（9097），在 WebView2 窗口中完成实时连接、速率、搜索、协议筛选、详情、暂停恢复、控制器失败恢复与 SQLite 诊断检查；真实会话采集 20 次快照、2618 条事件。发现并修复暂停后空结果文案错误和趋势图最新点对齐错误，详见[实机验收记录](../docs/desktop-acceptance-20261030.md)。没有修改代理配置、启动测速或切换节点；长时间运行和业务断流根因不属于这次短时验收结论。

新增依赖仅为本仓库 path 库和已使用的 serde；继承后端的锁定依赖与 SQLite bundled 构建行为。桌面 lockfile 增补后端依赖闭包，没有升级根项目 lockfile。接口用法核对了 [Tauri 官方 GitHub](https://github.com/tauri-apps/tauri) 与 [官方命令文档](https://v2.tauri.app/develop/calling-rust/)，未复制第三方实现代码。

界面为原创实现，框架采用官方 Tauri（https://github.com/tauri-apps/tauri，MIT / Apache-2.0）；未复制第三方界面代码。Tauri 的构建脚本生成平台资源并编译 Windows WebView2 桌面运行时适配。

启动日志优先追加到 EXE 同目录的 wiesler-startup.log，写入失败依次尝试 LOCALAPPDATA/Wiesler/desktop.log 和临时目录。记录 PID、时间、目录创建、WebView2 创建错误及 panic 堆栈。弹窗显示实际日志路径；所有候选路径均失败时显示写入失败原因。

### Windows 受限构建环境

2026-10-30 排查发现仓库内构建产物继承 Low Mandatory Level 标签，用户启动后也是低完整性进程，因此创建普通 AppData/WebView2 目录时报 os error 5。自签名不改变完整性级别。通过正式权限流程，在正常用户目录使用独立 target-dir 构建，不修改现有 ACL 或完整性标签：

```powershell
cargo build --manifest-path desktop/Cargo.toml --locked --offline --target-dir "$env:LOCALAPPDATA/Wiesler/build"
```

本机交付入口：`%LOCALAPPDATA%/Wiesler/build/debug/wiesler-desktop.exe`。重建后须重新自签名。旧 target、target-verified、target-logged 目录保留作诊断证据，不再作为推荐启动入口。
