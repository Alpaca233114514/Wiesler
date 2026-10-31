# AWS Lightsail VPS 维护规则

最后记录：2026-10-30（北京时间）。本文供维护者或 Codex 执行维护时参考；服务器现状可能变化，每次操作前先检查。

## 维护目标与边界

- 保持个人 WireGuard VPN 稳定运行，优先采用 Debian 官方软件包和系统自带工具。
- 主机 IPv4：`54.66.111.171`；SSH 用户：`admin`；本机 SSH 私钥：`C:\Users\Logan\.ssh\LightsailDefaultKey-ap-southeast-2.pem`。不要复制、展示或记录私钥内容。
- VPN 使用 `wg0`，IPv4 网段 `10.66.66.0/24`，服务器地址 `10.66.66.1`，UDP 端口 `51820`。Windows、Android 有各自密钥。公网出口接口上次检查为 `ens5`，每次核实实际默认路由，不要假设永远不变。
- 不修改 AWS 账户、计费和实例套餐；Lightsail 入站规则由用户管理。保留 TCP 22 和 UDP 51820，新增端口前取得授权。
- 不改变 SSH 端口或认证方式，不关闭现有防火墙，不为排障降低安全性。保留当前 SSH 连接，并在更改网络设置后用另一个 SSH 会话验证。
- 不输出私钥、凭据或完整客户端配置；不要将其写入 Git、公开日志或聊天。备份和配置文件仅允许所有者读取，传输使用 SSH 等安全通道。
- 删除、清空、覆盖已有文件，或涉及数据丢失的回滚，先说明具体路径、影响和可逆替代方案，取得用户明确授权。不要擅自 reset、clean 或 restore 丢弃工作。

## 自动维护现状

- `unattended-upgrades` 上次检查已启用，用于系统安全更新。每次维护应核实服务、更新配置和实际日志，不要假定所有软件都自动更新，也不要假定更新后已重启。
- `wg-quick@wg0` 已启用开机启动，配置了启动失败后重试。它是 systemd 一次性服务，不会持续检测接口被外部删除；不能把这一设置当作完整的健康监控或故障恢复。
- 未配置自动巡检、定时任务、自动重启或通知。若要增设，先明确监测方式、故障判定、恢复和通知范围，再实施；避免周期性重启和影响 SSH。

## 常规巡检

每次用户要求维护时，先检查，再按实际情况直接处理常规、可恢复的问题；不要为了普通检查反复确认。建议每月巡检一次，并在故障或安全更新后追加检查：

1. 检查系统、资源与网络：`uptime`、`df -h`、`free -h`、`ip -brief address`、`ip -4 route`，并确认时间同步、SSH 状态和监听端口。
2. 检查服务：`systemctl is-enabled wg-quick@wg0`、`systemctl status wg-quick@wg0 --no-pager`、`sudo journalctl -u wg-quick@wg0 -p warning --since '30 days ago' --no-pager`。
3. 检查转发及 NAT：`cat /proc/sys/net/ipv4/ip_forward`、`sudo iptables -S FORWARD`、`sudo iptables -t nat -S POSTROUTING`。核对当前默认出口与 wg0 规则中的接口一致，避免影响已有规则。
4. 检查最近握手及流量：`sudo wg show wg0`。无近期握手可能只是客户端没有连接，不应单独判定故障。不要运行或展示会输出私钥的命令，如 `wg showconf`。
5. 在用户同意使用测试客户端时，验证公网握手、HTTPS 访问和出口 IPv4；单纯服务 active 或本机监听不等于端到端成功。核实 DNS 和 IPv6 风险，明确实测范围。
6. 检查 `systemctl --failed`、`unattended-upgrades` 状态及日志，记录磁盘空间和待更新情况；只报告有意义的变化、问题和未验证项。

## 更新与重启

- 安装和更新前确认来源及行为，预览变更：`sudo apt update`、`apt list --upgradable`、`sudo apt-get -s upgrade`。软件仅使用可信官方仓库，不执行未经检查的远程脚本。
- 常规安全更新优先交给现有 `unattended-upgrades`；必要时定向更新 WireGuard：`sudo apt install --only-upgrade wireguard-tools`。涉及软件移除、重大版本迁移或明显服务中断时先评估影响；破坏性操作须先获明确授权。
- 更新前备份受影响配置并检查备份权限。更新后验证 SSH、服务、规则和客户端连通性；内核更新可能需要重启，重启前告知影响并选择合适时间，不以安装成功替代重启验证。
- 手动重启 VPN：`sudo systemctl restart wg-quick@wg0`。这会短暂中断 VPN，不应影响独立 SSH；操作后验证 SSH 和隧道。

## 配置、备份和交付

- 服务端配置：`/etc/wireguard/wg0.conf`，通常 root 所有且权限 `600`；转发配置：`/etc/sysctl.d/90-wireguard-forward.conf`；systemd 覆盖：`/etc/systemd/system/wg-quick@wg0.service.d/recovery.conf`。
- 服务端客户端文件：`/home/admin/wireguard-clients/`；Windows 本机副本：`C:\Users\Logan\.ssh\wireguard-lightsail\`。每个设备的 `.conf` 和 `.yaml` 是同一客户端身份的两种格式，不要同时使用。
- 首次部署前状态备份：`/root/wireguard-preinstall-20261030T134906Z`，含当时转发值、路由及 iptables 规则，不含服务端密钥。配置变更前另做带时间的私密备份，备份服务端配置及所需客户端配置，限制访问并避免覆盖旧备份。
- 不把备份或配置放进 Git 仓库、公开存储或普通日志。备份恢复前核对目标和当前状态，不整套覆盖现存防火墙规则。
- 最初曾添加 sing-box 官方 APT 源，但未安装 sing-box。未经明确授权不删除该源或相关文件。

## 故障排查和回滚

- 服务启动失败：查看 `systemctl status wg-quick@wg0` 和 journal；核对配置权限、UDP 端口占用、出口接口以及转发/NAT 规则。不要输出配置中的私钥。
- 无握手：确认客户端配置、网络可用性、UDP 51820 的 Lightsail 入站规则和服务器监听。只有用户能操作控制台时，把具体规则交给用户。
- 有握手但不能上网：检查 IPv4 forwarding、NAT、FORWARD 规则、返回路由、客户端 DNS 和 MTU；逐项验证，避免重置防火墙。
- 暂停服务：`sudo systemctl stop wg-quick@wg0`；恢复：`sudo systemctl start wg-quick@wg0`。暂停会断开 VPN；SSH 应保持独立。完全回滚前先检查当前配置和备份，说明将要修改的具体路径与操作，并在删除或覆盖前取得授权。
- 当前未配置 IPv6 隧道。IPv4 的 `0.0.0.0/0` 不保护设备的 IPv6 流量；客户端可能 IPv6 直连。Mihomo 配置的 `ipv6: false` 不会管控其他应用。DNS 也可能绕过隧道，应在实际设备上单独验证，不宣称不存在泄漏。

## 最新已知验证

2026-10-30：Windows Mihomo 配置经公网握手成功，通过隧道访问 HTTPS，出口 IPv4 为 `54.66.111.171`；两份 YAML 通过本机 Mihomo 配置检查。Android 和官方 WireGuard 客户端尚未实机验证，DNS 全部经过隧道及 IPv6 防泄漏未得到证明。后续维护以新检查结果为准。
