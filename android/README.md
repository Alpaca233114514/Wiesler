# Wiesler Android

Kotlin 原生 Jetpack Compose + Material 3 Expressive Android 客户端，观察手机自己的网络，最低 Android 11（API 30）。不启用 VpnService，不替换或修改手机现有代理。

## 功能（Material 3 Expressive + PC 同步）

- **图谱**：采用 Material 3 Expressive 界面与弹簧动效（`MaterialExpressiveTheme` / `MotionScheme.expressive()`），默认实时连接。同步 PC 桌面端能力：顶部双路公网出口对比卡（系统路由 vs 代理路由 IP、国家/城市/ISP 与是否分流）、暂停采集/冻结独立控制、同域名不同端口自动堆叠节点与检查器内切换、细线逻辑路由路径条（手机 → 策略组 → 出口 → 服务器）、逐连接 60 帧速率微折线、可开关实时脉冲粒子、来源筛选（全部 / DIRECT 直连 / 代理转发 / 海外出口 / 其他）与协议筛选，以及最近 60 帧时间条回放与本地演示模式。
- **监测**：同步 PC 运行监测的 Mihomo 总览指标与渐变面积趋势图，保留 Android 独有的全局流量（`TrafficStats`）、海外转发流量（指定 `chains[0]` 出口差分）、今日应用流量排行（`NetworkStatsManager` + 波浪进度条）与 ICMP 丢包/平均 RTT 监控，并展示最近 100 条控制器/HTTPS 请求日志。
- **隐私**：同步 PC 隐私与 DNS 核查能力。支持一键并发执行「主动网络检查」（读取 Android `LinkProperties` 系统 DNS、Private DNS 状态、实测 `example.com` 解析耗时、双路公网出口对比、本机与出口时区偏移对比）、连接快照核查（53 端口线索、全代理预期策略、可配置上传速率告警阈值与可展开连接证据）、系统网络快照与按需 HTTPS 204 探测、设备环境暴露面自检，以及通过 SplitButton 分享文本报告或导出/分享结构化 JSON 隐私证据。明确标注平台边界：Android 非 root 且不接管 VPN，无法抓取他应用明文 DNS 底层报文。
- **历史**：同步 PC 历史会话诊断能力。通过系统文件选择器（SAF）只读打开 Wiesler CLI 生成的 `.sqlite3` / `.db` 证据库，校验 `WISL` 应用标识与 schema v1，列出历史会话并在手机端直接生成与分享 Markdown 诊断报告。
- **设置**：支持并发扫描本机回环常见 Mihomo 控制器端口（严格校验 `/version` 与 `/connections`，扫描不携带密钥）、手动配置控制器与 Keystore AES-GCM 加密 Secret、管理海外出口节点、开关图谱脉冲动效，以及切换 Material 3 Expressive 深色/浅色/跟随系统配色与 Android 12+ Material You 动态取色。

## 数据边界

诊断中心的请求日志在手机本地保留最近 100 条控制器 / HTTPS 检查结果，可随诊断报告分享。显示 HTTP 200、401、403 等实际状态码，成功为绿色、失败为红色；超时或网络错误无状态码时显示「未取得状态码」。控制器连接状态也保留具体 HTTP 数字。退出或重建 Activity 后恢复日志，不保存请求头或响应正文。控制器响应码不代表代理连接所访问网站的响应码。

### 开启海外转发监控

1. 概览点击「控制器」，填写手机 Mihomo 的 External Controller 地址和 Secret。
2. 点击「海外出口」，从当前连接中选择实际海外出口节点；没有活跃连接时可「手动填写」，每行一个准确节点名。选择的是实际出口 `chains[0]`，不是代理组名。
3. 打开「海外转发流量」开关；取得连续快照后显示速率与已观测累计。点击「查看转发连接」可直接排查该范围的连接。

海外转发默认关闭；全局和转发开关互不影响，也不切换代理或路由。全局关闭后仍可观察海外转发。转发关闭且不在连接页时，不再周期读取控制器。出口选择保存在手机本地；更换控制器地址会清除旧出口选择。

转发只统计用户指定的实际出口，不自动推断节点或目标的地理位置。按连接 ID、开始时间和出口匹配后计算字节增量，不对随连接增减变化的累计总和直接求差。新出现的连接先建立基线；采样间已结束的连接、关闭前最后一段以及缺口内的流量可能漏计，因此展示「已观测」累计。该值不是完整的出口账单，两张图也不能相减得到直连流量。控制器读取失败时显示原因与断点，不填充为零；正常空连接快照连续采样后才显示零速率。

后台、开关切换、失败恢复均重新建立差分基线；更换出口集合或控制器地址重置转发历史和累计；保持相同选择或地址时保留。图表横向为最近 60 次采样，控制器响应慢时不等同于一分钟；图表、已观测累计、开关、出口选择、上次页面和控制器连接设置在重启 App 后恢复。控制器 Secret 使用 Android Keystore 的 AES-GCM 加密保存，密文绑定控制器地址；点击断开会移除保存的 Secret 并停止自动重连。

仅在前台采样；后台和暂停恢复重建差分基线，计数回退、无效值和超过 5 秒的缺口不计算速率。VPN 场景下系统接口累计可能重复计量，不等同套餐账单。应用系统统计可能延迟；共享 UID 合并，包可见性限制可能导致名称未知。VPN 开启状态不代表代理可用或出口已验证。

控制器只发送 GET，不切换节点、关闭连接或修改配置。明文 HTTP 仅允许回环地址，远程地址必须 HTTPS 且使用系统证书验证。鉴权 Secret 仅以密文保存，不进入日志、命令行或诊断报告；解密失败时停止自动连接并提示重新配置。失败清空连接，取消、后台或重连后忽略旧请求结果。

手机没有开放控制器时仍可使用概览和诊断；应用排行需要使用情况访问权限。桌面的 Rust SQLite 历史诊断、DSH / Codex 日志接入尚未移植。

## ICMP 丢包率监控

概览和诊断中心均提供「ICMP 丢包率」卡片，填写单播 IPv4（默认 `1.1.1.1`）并点击「开始监控」，不要求连接 Mihomo。调用系统 `/system/bin/ping`，每次 1 个请求、回包超时 2 秒，每轮完成后间隔 1 秒继续。无需 root 或安装新依赖。

最近 60 次已完成探测显示丢包率、发送、收到、未回包、探测错误和成功回包平均 RTT。丢包率 = 未回包 /（收到 + 未回包）；无法执行 Ping、缺少权限、路由错误或无法解析输出不计入分母，没有有效样本显示 `—`。不同系统禁用 Ping 时明确显示探测错误。窗口按样本数滚动，不是固定一分钟。

停止或切入后台会取消当前进程并忽略旧结果。回前台需要主动重新开始，新一轮重置窗口；目标地址保存在本机，采样结果只在当前 Activity 内保留，重建或退出后不恢复。诊断分享包含当前目标、统计和最后状态。

ICMP 走系统路由，不保证经过 HTTP / SOCKS 代理。禁 Ping、目标限速和路径过滤也会造成未回包，不能直接等同业务或 VPN 隧道丢包率。目前只支持 IPv4 地址。

## 构建

使用 JDK 17 或 21、Android SDK 36 / Build Tools 35.0.0、Gradle 8.14、AGP 8.13.2、Kotlin 2.2.20。`local.properties` 中设置 SDK 路径（不提交）。依赖来自 Google Maven / Maven Central / Gradle 官方分发；本机开发使用已有缓存离线构建，不执行远程安装脚本。

```powershell
$env:JAVA_HOME='D:\AndroidStudio\jbr'
.\gradlew.bat --offline --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n io.wiesler.android/.MainActivity
```

仅 APK 安装和启动需 USB 调试，日常监测不依赖电脑或 USB。

## 来源与依赖

开发前检查了 GitHub 上的 Mihomo / Android 相关项目；本客户端 UI、采样和控制器实现为原创，没有复制这些项目代码或嵌入代理内核。

- Gradle Wrapper JAR：来自本机既有 Gradle wrapper；上游 [Gradle](https://github.com/gradle/gradle)，Apache-2.0，版权 Gradle 原作者与贡献者。完整许可见仓库根目录 `LICENSE`。Wrapper 仅用于构建，不打入 APK。
- Kotlin 标准库与 Compose 编译器插件 2.2.20：[JetBrains/kotlin](https://github.com/JetBrains/kotlin/tree/v2.2.20)，Apache-2.0。随 APK 打包，`assets/NOTICE.txt` 和 `assets/LICENSE-Apache-2.0.txt` 包含来源与许可。
- AndroidX Jetpack Compose（BOM 2026.06.01，含 `ui`、`foundation`、`animation`、`material-icons-extended:1.7.8`）、`androidx.compose.material3:material3:1.5.0-alpha16` 与 `androidx.activity:activity-compose:1.13.0`：Google 官方 AndroidX（Apache-2.0），通过 `app/gradle.lockfile` 锁定全部传递依赖版本。
- AGP 8.13.2：Google 官方 Android 构建插件，Apache-2.0；编译、资源处理、DEX 和 debug 签名，无自定义安装脚本。
- JUnit 4.13.2（EPL-1.0）与 `org.json:json:20250517`（Public Domain）：仅本地 JVM 单元测试，不打入 APK。

API 口径核对：[TrafficStats](https://developer.android.com/reference/android/net/TrafficStats)、[NetworkStatsManager](https://developer.android.com/reference/android/app/usage/NetworkStatsManager)。

## 本地持久化

设置变更时保存，图表和日志每 5 秒及进入后台时保存。强制终止可能丢失最后约 5 秒的样本；首次升级前旧版仅存在内存中的历史无法追回。重启后速率重新建立基线，历史曲线插入断点，不统计关闭期间的流量。保留最近 60 个图表样本和 100 条请求日志，不是无限历史归档；实时连接列表重新向控制器读取。数据保存在应用私有目录，不上传，沿用禁用系统备份设置。

开发前查看了 GitHub 的 Android Keystore / AES-GCM 实现；本轮未复制第三方代码、未新增依赖，使用 Android 平台 API。
