# 隐私检查初步实现

桌面工作台新增「隐私检查」，复用当前 Mihomo 与系统 TCP 快照。界面随采样更新，不受图谱搜索、冻结或回放影响；顶部采集状态与报告中的时间、来源、暂停状态用于判断证据时效。检查条件只在当前窗口内保留。

- 目标端口 53：标为 DNS 端口线索，不把端口当作 DNS 查询内容。
- 用户勾选「所有已观测连接应经代理」后，Mihomo 报告的 DIRECT 才产生策略差异提示。合法分流或本地连接也可能触发，需按实际预期使用；DIRECT 不等于物理网卡绕过 VPN。
- 单连接上传速率超过所设阈值：显示调查线索。沿用连续快照差分，首次、间隔过大、计数回退不报警；不将上传量等同泄密。
- 每条线索可展开进程、PID、源/目标端点、协议、逻辑路由、规则、连接 ID 和时间。未知系统路由保持未知。
- 本地环境自检读取当前页面的 UA、语言、时区和屏幕信息，不发送外部请求，不代表其他浏览器，不计算匿名性评分。
- 「生成 JSON 报告」保留当前条件、覆盖范围、采集错误、来源与证据，可手动复制；报告包含网络元数据，分享前自行检查。

这版没有 ETW/WFP、DNS 查询正文与实际接口采集，没有 DoH/WebRTC、跨会话指纹检测，也没有拦截、修改或伪造环境。无提示不能证明无泄漏。系统套接字采样可能漏掉短连接，Mihomo 只能提供其已观测连接。Android 尚未移植。

开发前查看了引用对话所指向的 https://github.com/yt1988/who-are-you ，确认其网页检测方式与本机连接证据的区别。本轮原创实现，未复制第三方代码或引入依赖。

验证命令：

```powershell
node --test --test-isolation=none desktop/tests/ui.test.cjs desktop/tests/studio.test.cjs desktop/tests/privacy.test.cjs
```

# Windows 发行者与签名

桌面 `bundle.publisher` 使用已有本机证书的名称 `Wiesler Local Demo`，对应 Windows EXE 的 CompanyName。重建会使旧签名失效，应在构建完成后调用 `scripts/sign-desktop.ps1`，传入 EXE 与 Windows SDK 的 signtool 路径。脚本使用当前用户证书存储中的已有私钥，验证签名者、时间戳和 WinVerifyTrust 返回值，不导出私钥。

已有证书为自签名；它能标识签名者，但不提供受公共 CA 信任的发行者身份，也不保证消除 SmartScreen。脚本不安装信任根或更改 SmartScreen 设置。公开发行需采用受信任的代码签名身份或商店发行流程；即使有效 OV/EV 签名，新文件仍可能等待信誉积累。参见 [Microsoft SmartScreen 说明](https://learn.microsoft.com/windows/apps/package-and-deploy/smartscreen-reputation)。
