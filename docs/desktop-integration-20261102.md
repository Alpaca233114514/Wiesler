# Wiesler Desktop 融合与自动发现（2026-11-02）

## 结果

`studio.html` 是默认桌面入口，启动后立即读取本机 TCP 连接，同时自动发现代理控制器。控制器扫描、选择、重连与高级手动地址统一放在「设置」；主视图只保留状态和采集控制。

- 应用、窗口标题与主界面品牌统一为 Wiesler Desktop。图谱、运行监测、历史诊断和设置在同一页面切换，不重新创建采集会话。
- 公网出口分系统路径和代理路径探测，显示 IP、国家/地区、城市、运营商和采样时间，并支持刷新。代理端口通过已连接控制器 `/configs` 的 mixed-port/port 自动读取。
- 缺失进程名按 Windows PID 表、唯一 TCP 连接或 UDP 本地端点所有者补全；没有可靠映射时保留 PID 与未识别原因。
- 系统 TCP 和 Mihomo 分别采集；控制器异常不清空有效的系统连接。暂停/隐藏使在途结果失效，恢复重新建立速率基线。
- 同域名忽略大小写和尾随点合并成堆叠节点，端口不参与分组。检查器下拉框切换具体连接，会话表仍逐条保留。
- 同一应用到同一域名的图谱边聚合展示；有当前流量增量时显示上传/下载两个方向的移动点，冻结、回放、暂停时停止，不代表逐包动画。
- 检查器的连接路径使用细轨道、小圆点和必要名称；会话表改成紧凑四列，最后一列是逐连接双向速率折线，缺口不连线，保留历史身份边界。Mihomo 的叶节点在前的链反向展示为电脑 → 逻辑路由节点 → 服务器；DIRECT 显示直连两端，系统未知路径保留待确认节点。原始 JSON 不改序。
- 请求日志、ICMP、SQLite 会话读取及诊断复用现有 Rust 命令；公共界面逻辑抽出到 `monitor-tools.js`，旧 `index.html` 保留兼容入口。

滚动归属修正仅调整容器：顶栏/导航固定，内容区保留竖条，检查器和会话列表保留自己的滚动；已回滚此前压缩面板高度和自适应减少图谱节点的布局变更。

移动点保存在独立 SVG 图层，连续采样不重建在途动画；轨道延伸到节点内部，流量停止后完成当前行程再移除。窗口设置深色原生背景并在页面加载完成后显示，以避免初始白底。

## 自动发现与鉴权

Windows 使用 `netstat -ano` 枚举监听端点，仅探测回环 IP；通配监听地址转换为对应回环地址，不访问局域网目标。每批最多 8 个端点，单次 HTTP 请求超时 800 ms。`/version` 和 `/connections` 都有效才标记可用；只有一个可用控制器时自动连接，多个结果供选择。普通 HTTP 服务、混合代理入口和未经验证的鉴权响应不会被自动认定为可用控制器。

后端读取 `%APPDATA%/io.github.clash-verge-rev.clash-verge-rev/clash-verge.yaml` 的顶层 `external-controller` 和 `secret` 标量，仅支持普通、单引号或双引号标量；复杂 YAML 表达式不作为凭证来源。不加载订阅列表，不修改原配置。配置密钥只用于对应的回环端点，未知监听端口的探测不带凭证。已有 `WIESLER_MIHOMO_SECRET` 保持显式覆盖能力。密钥不进入 IPC 返回值、前端、日志或命令参数。

## 数据口径

Windows 系统来源仅观察已建立 TCP 连接，包含进程、PID 和两端地址；UDP 绑定端点仅用于补全控制器连接的进程归属，不伪造系统 UDP 远端会话；不提供系统逐连接字节计数。系统记录没有控制器证据时显示路由待确认，不能凭此推断是否经过 TUN/VPN。

Mihomo 来源区分叶节点为 DIRECT 的已确认直连与代理转发。只有源端点、目标/回环端点以及可用 PID 证据能唯一匹配时才合并系统与控制器记录；有歧义则保留独立记录。全局流量卡片和趋势明确标注为 Mihomo 数据，系统来源不伪造流量数值。

路由条表达控制器逻辑链，其中可能包含策略组，不等同于 traceroute 或已经验证的物理服务器跳点。链的顺序核对了 [Mihomo Chain 定义](https://github.com/MetaCubeX/mihomo/blob/Meta/constant/adapters.go)和 [Selector 追加链的实现](https://github.com/MetaCubeX/mihomo/blob/Meta/adapter/outboundgroup/selector.go)。未复制第三方代码。

## 出口探测

使用系统已有 curl 查询 [ipify 官方公开 IP 接口](https://www.ipify.org/)，优先 `https://api64.ipify.org`，失败再尝试一次 `https://api.ipify.org`；每次连接超时 5 秒、总请求上限 10 秒。系统路径显式清空 HTTP 代理，代理路径显式使用控制器配置中的本机代理端口；两者都保留 TLS 验证、不读 curlrc，不向外部服务发送控制器凭证。来源端点随结果返回，UI 仅展示有效 IP，不展示响应正文。

系统路由可能被 TUN/VPN 接管；代理路径沿用当前 Mihomo 路由。因此 IP 结果只证明这次查询请求的出口，不将其绑定到全部业务连接，也不把两者相同或不同直接解释成代理成功/失败。

有效出口 IP 使用 [IPWhois 官方接口](https://ipwhois.io/documentation)查询国家/地区、城市和运营商，只接受对应同一 IP 的响应；不采集经纬度字段。查询失败不丢弃已取得的 IP。地理位置来自地址库，不能当作设备的精确物理位置。

## 验证

- 前端 `node --test --test-isolation=none desktop/tests/ui.test.cjs desktop/tests/studio.test.cjs`：45 项通过。覆盖共享采集、迟到响应、错误恢复、冻结与实时错误状态、独立系统连接、发现后的自动连接、多个候选、设置视图、路径顺序、同域名跨端口堆叠和逐连接检查。
- 根项目 `cargo test --locked --offline`：47 项通过。
- 桌面测试：8 项通过；2 项真实环境验收测试默认忽略，另外显式运行本机控制器/系统命令和公网出口测试。
- 根项目和桌面 `cargo clippy ... --all-targets -- -D warnings` 通过；Rust 格式和 diff 空白检查通过。
- 本机验收直接调用实际桌面命令：扫描 36 个监听端点，识别并认证 `http://127.0.0.1:9097`，成功读取 Mihomo 快照；系统采集返回 314 条已建立 TCP 连接；`127.0.0.1` ICMP 收到回包；从独立测试库读取 1 个会话并生成非空诊断。数量是该次采样结果。后续又验证 Windows PID/UDP 所有者数据返回。
- 公网出口真实探测初次系统路径成功、代理路径超时；追加单次备用 IP 查询后，系统和代理探测均返回有效 IP；后续两侧地理查询也显式实测通过。这是短时探测结果，不是 TLS 或网络稳定性的永久修复证据。
- 独立窗口已实际显示“实时 · Mihomo”、系统 TCP 数量、DIRECT/代理/系统连接及设置入口，主视图无控制器地址输入框。检测到用户同时操作后停止自动交互，未将未完成的设置点击和筛选操作记为实机通过。后续独立预览窗口还实际显示同域名堆叠（5 条连接、2 个端口）、端口下拉框和 DIRECT 的电脑 → 服务器细轨道；未完成代理中间节点和端口切换的人工操作验收。

真实控制器验收不是长时间稳定性、业务 TLS 故障或所有 VPN 路由的验收。系统连接、ICMP 与控制器数据的观测口径分别保留。

## 运行与开发

本轮正在运行的旧程序保留，独立构建目录为 `%LOCALAPPDATA%/Wiesler/build-integration-20261102`。最终滚动归属修正版本使用单独输出文件：

`%LOCALAPPDATA%/Wiesler/build-integration-20261102/ready/Wiesler Desktop.exe`

```powershell
cargo build --manifest-path desktop/Cargo.toml --locked --offline --target-dir "$env:LOCALAPPDATA/Wiesler/build-integration-20261102"
# 当前主程序被占用时，保留正在运行的窗口，使用不同输出文件。
cargo rustc --manifest-path desktop/Cargo.toml --locked --offline --target-dir "$env:LOCALAPPDATA/Wiesler/build-integration-20261102" --bin wiesler-desktop -- -o "$env:LOCALAPPDATA/Wiesler/build-integration-20261102/ready/Wiesler Desktop.exe"
```

显式本机验收只读访问已运行的控制器，可选提供本轮测试库路径（不含密钥）：

```powershell
$env:WIESLER_TEST_DATABASE = Join-Path (Get-Location) 'target/integration-20261102/session.sqlite3'
cargo test --manifest-path desktop/Cargo.toml --locked --offline --target-dir "$env:LOCALAPPDATA/Wiesler/build" local_controller_acceptance -- --ignored --nocapture
```

实现前检查了 [Clash Verge Rev](https://github.com/clash-verge-rev/clash-verge-rev) 等现成项目，本轮复用本仓库的 Tauri/Mihomo/诊断实现，没有复制第三方实现、增加依赖或升级 lockfile。

## 试用包启动复查（2026-11-02 晚）

仓库 `dist` 中的 EXE 继承了工作区的低完整性标签。资源管理器双击后，进程同样以低完整性运行，创建普通用户 WebView2 数据目录时报 `PermissionDenied (os error 5)`。此前仅确认 Release 编译、签名和 CLI 启动，没有验证该桌面交付路径，不能算桌面启动验收。

将原签名 ZIP 经正式权限审核解压到独立的普通用户试用目录后，未修改 ACL、完整性标签或系统防护，也未清空已有数据。同一程序成功创建 WebView2 主窗口，实际界面显示实时 Mihomo、系统 TCP、连接图谱和出口信息。交付时应从 ZIP 解压后的正常用户目录启动并核验实际窗口，不应将受限工作区内的 EXE 链接当作已验收的安装入口。
