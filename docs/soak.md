# 节点浸泡测试

`wiesler nodes soak` 实现[节点实测记录](node-selection-20261030.md)的第三阶段：一次持续下载，然后串行发起多次真实 HTTP 请求。默认下载 **60,000,000 字节**，上限 90 秒，随后发起 **15 次**请求，每次上限 20 秒；不重试、不在样本间冷却。需要 PATH 中已有 `curl.exe`（Windows）或 `curl`（其他平台），支持 HTTP/HTTPS 和 `--max-filesize`。不增加 Cargo 依赖。

## 使用

控制器和代理入口必须都是回环 IP 的 HTTP 根地址，且是两个不同用途的端口。控制器密钥仅从 `WIESLER_MIHOMO_SECRET` 读取，不传给 curl。示例端口来自本机历史实测，运行前核实当前配置。

```powershell
# 当前路由测试：不切换组选择，仍会产生真实测速流量。
.\target\debug\wiesler.exe nodes soak --controller http://127.0.0.1:9097 --proxy http://127.0.0.1:7897 --group '🔰 节点选择' --out current-soak.json

# 单个候选默认只预览；不会下载，也不写控制器。
.\target\debug\wiesler.exe nodes soak --proxy http://127.0.0.1:7897 --group '🔰 节点选择' --node '🇨🇦 加拿大' --out candidate-plan.json

# 已有 stable.json：按 fail、p90、median 升序取前五，默认只预览。
.\target\debug\wiesler.exe nodes soak --proxy http://127.0.0.1:7897 --group '🔰 节点选择' --from 'C:\Users\Logan\codex-node-probe\stable.json' --top 5 --out batch-plan.json

# 显式执行批量切换；正常结束、失败或 Ctrl+C 后尝试恢复运行前选择。
.\target\debug\wiesler.exe nodes soak --proxy http://127.0.0.1:7897 --group '🔰 节点选择' --from 'C:\Users\Logan\codex-node-probe\stable.json' --top 5 --apply --bytes 60000000 --pings 15 --out batch-soak.json

# 比较阶段可以使用 25 次请求；--node 可重复，按参数顺序执行。
.\target\debug\wiesler.exe nodes soak --proxy http://127.0.0.1:7897 --group '🔰 节点选择' --node '🇨🇦 加拿大' --node '🇨🇳 台湾01 -ANYTLS 0.8倍率' --apply --pings 25 --out comparison-soak.json
```

`--dry-run` 可显式预览当前选择或候选。预览仍需读取控制器版本、当前选择和候选类型，以拒绝不存在的节点或代理组。`--from` 与 `--node` 互斥；`--apply` 必须配合其中之一。仅支持 `Selector` 组和组内的具体节点，拒绝候选中的嵌套代理组、DIRECT、REJECT 等条目。

`--from` 接受原实测脚本 `stable.py` 的 JSON 数组，例如：

```json
[
  {"name": "🇨🇦 加拿大", "ok": 12, "fail": 0, "p90": 3656, "median": 1661},
  {"name": "🇨🇳 台湾01 -ANYTLS 0.8倍率", "ok": 11, "fail": 1, "p90": 1896, "median": 913}
]
```

跳过 `ok=0`，同名条目去重，再取 `--top`。成功样本的 p90/median 可为 null，此时排在有数值的同失败数条目之后。不隐含排除香港或任何地区；如需排除，在输入中选择相应候选或使用 `--node`。目前没有实现 `nodes list/probe/select`，也不会自动生成 `stable.json`。

## 度量口径

- `--download-url` 默认是 `https://speed.cloudflare.com/__down?bytes=<--bytes>`，`--bytes` 改变时自动调整。自定义下载 URL 时，由调用者保证其返回对应大小。
- `--url` 默认是 `https://chatgpt.com/cdn-cgi/trace`；仅用于重复请求，不解析或保存响应正文、trace IP 或地区。
- 下载只有 **curl 成功退出、HTTP 2xx、完整接收恰好指定字节数** 才算成功。提前结束、超额响应、超时和错误页都不算完成。不沿用原脚本“超过 20 MB 即视为 60 MB 成功”的宽松判断。
- 每个请求使用独立 curl 进程，不跨请求复用连接，传输正文丢弃到平台空设备；Windows 使用 `nul`。禁用 curlrc、自动重试、自动代理绕过及重定向，保留系统默认 TLS 证书校验。显式代理覆盖环境中的 `NO_PROXY`。
- 原始样本保存 UTC 毫秒时间、round、curl 退出码、HTTP 状态、接收字节数、curl 墙钟耗时和吞吐，不保存 curl stderr 或控制器错误正文。典型分类包含 `timeout`、`tls_handshake`、`tls_certificate`、`http_4xx`、`http_5xx`、`size_mismatch`、`incomplete_transfer`、`receive_error`。
- `median_ms` 为成功请求的通常中位数，`p90_ms` 用 nearest-rank（第 `ceil(0.9×N)` 个），`stdev_ms` 为总体标准差；没有成功请求时均为 null。吞吐单位 `soak_kibps` 是 KiB/s（1024 字节）。失败不参与延迟统计，单独计数。
- `measurement=curl_wall_clock_ms_fresh_connection_no_retry`，`concurrency=1`。这与 Mihomo delay 端点的统一延迟不同，不能直接混排。

## 选择恢复与结果文件

实际测试在当前用户临时目录的 `wiesler-nodes-soak.lock` 上持有操作系统文件锁，串行执行。可用 `--lock-file` 指定共同路径；所有协作实例必须使用相同路径。进程退出会释放锁，不删除锁文件。它不能阻止其他客户端、用户手动操作或其他电脑切换节点。

`--apply` 在每次 PUT 前把原选择、预期选择及 `from → to` 记录写入输出，并在每次样本结束后刷新。测量前后核对选择；发现外部改动或控制器不可读时停止，已测数据标为不完整。恢复时只有当前选择仍属于本次写入的预期状态（包括 PUT 被拒绝时的前值）才写回原选择，避免覆盖检测到的外部改动。Mihomo 不提供比较并交换接口，检查与 PUT 之间仍有竞争窗口，恢复是尽力而为。

JSON 输出只允许新建，已有文件不会被覆盖。运行中的文件会刷新自身内容，不能保证写入瞬间或强制终止时 JSON 完整；不要把它当作事务数据库。`selection_changes`、`original_selection`、`expected_selection` 与 `restoration` 保留切换及恢复证据，不写入现有 SQLite 观察事件，也不自动选出或应用获胜节点。

`status` 为 `preview` / `completed` 时 CLI 返回 0；存在测试失败、取消、选择冲突或恢复失败时返回非零。其他状态包括 `running`、`completed_with_failures`、`cancelled`、`incomplete`。`restoration` 的 `not_needed`、`pending`、`restored`、`failed`、`conflict` 区分是否需要恢复及结果。发生 `failed/conflict` 时，先检查 JSON 的原选择和当前控制器状态，再人工处理。强制结束进程、关闭控制台、断电不保证执行恢复；最后一次 `pending` 记录可用于核对。

## 证据边界与验证

设置某个 Selector 并不能证明测试 URL 实际经过它；测试前仍需核实规则路由。报告固定保留 `route_verified=false`，结果名称表示当时观察到的组选择，不能当作精确出口归属。组选择检查也不能发现两次检查之间短暂切换又切回的情况。旧输入中的 profile 新鲜度和订阅更新状态未知，报告用 `input_freshness=unknown_not_verified` 明示；不会根据输出时间推断订阅有效。

本次 Windows 隔离验证使用随机回环端口上的模拟控制器和 HTTP 代理，覆盖默认预览、候选排序、Unicode/特殊字符编码、完整与不足字节、HTTP 503、超时、取消标志、恢复失败、第二次切换被拒、外部选择冲突、互斥锁、旧输出保留及凭证隔离。CLI 端到端测试也验证显式代理覆盖 `NO_PROXY=*`，并区分成功/失败退出码。取消测试验证与 Ctrl+C 共用的取消标志路径，没有向真实终端发送 Ctrl+C。没有切换真实 Mihomo、进行公网下载、验证真实 TLS 握手失败或完成多小时测试。

2026-10-30 验证结果：全套 40 项 Rust 测试通过（新增 11 项浸泡测试），fmt、Clippy（`-D warnings`）及 `git diff --check` 通过；现有 `scripts/verify_p1.py` 的 7 组模拟验收也通过，产物位于 `target/p1-acceptance/1793360210683645700/`。回环网络测试在受限沙箱中无法连接，已通过正式权限申请后在正常本机权限下运行。构建有增量缓存硬链接失败改为复制的环境提示，无 Clippy 代码告警。

```powershell
cargo test --locked --offline
cargo fmt --all -- --check
cargo clippy --locked --offline --all-targets -- -D warnings
```

接口对照 [Mihomo 官方 proxies 路由](https://github.com/MetaCubeX/mihomo/blob/Meta/hub/route/proxies.go)；GitHub 调研也查看了 [proxy-test](https://github.com/MarkIvory2973/proxy-test) 等短延迟工具，本功能未复制这些项目的代码。原实测脚本只作本项目需求与行为参考，没有把硬编码路径或密钥迁入实现。
