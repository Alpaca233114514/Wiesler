# Wiesler Android

Kotlin 原生 Android 客户端，观察手机自己的网络，最低 Android 11。不启用 VpnService，不替换或修改手机现有代理。

## 功能

- 概览：全局流量与海外转发流量两张图，各有独立开关，显示下载 / 上传速率和最近 60 次采样；全局使用 Android TrafficStats，海外转发使用指定 Mihomo 出口的连接增量。
- 应用：用户在系统界面授予使用情况访问后，读取今天零点至今的 NetworkStatsManager 统计，按 UID 聚合 Wi-Fi 与移动数据；无法解析应用名称时保留 UID。
- 连接：可选接入手机 Mihomo HTTP 控制器，读取 `/connections`，支持搜索、TCP / UDP、直连 / 海外转发 / 其他筛选和详情，列表显示实际出口。不开启控制器时不生成模拟连接。
- 诊断：当前接口、地址、DNS、Private DNS、MTU、系统网络验证；用户主动点击后检查一次 HTTPS 请求，支持系统分享报告。

## 数据边界

诊断中心的请求日志保留当前 Activity 内最近 100 条控制器 / HTTPS 检查结果，可随诊断报告分享。显示 HTTP 200、401、403 等实际状态码，成功为绿色、失败为红色；超时或网络错误无状态码时显示「未取得状态码」。控制器连接状态也保留具体 HTTP 数字。退出或重建 Activity 后日志不保留，不保存密钥、请求头或响应正文。控制器响应码不代表代理连接所访问网站的响应码。

### 开启海外转发监控

1. 概览点击「控制器」，填写手机 Mihomo 的 External Controller 地址和 Secret。
2. 点击「海外出口」，从当前连接中选择实际海外出口节点；没有活跃连接时可「手动填写」，每行一个准确节点名。选择的是实际出口 `chains[0]`，不是代理组名。
3. 打开「海外转发流量」开关；取得连续快照后显示速率与已观测累计。点击「查看转发连接」可直接排查该范围的连接。

海外转发默认关闭；全局和转发开关互不影响，也不切换代理或路由。全局关闭后仍可观察海外转发。转发关闭且不在连接页时，不再周期读取控制器。出口选择保存在手机本地；更换控制器地址会清除旧出口选择。

转发只统计用户指定的实际出口，不自动推断节点或目标的地理位置。按连接 ID、开始时间和出口匹配后计算字节增量，不对随连接增减变化的累计总和直接求差。新出现的连接先建立基线；采样间已结束的连接、关闭前最后一段以及缺口内的流量可能漏计，因此展示「已观测」累计。该值不是完整的出口账单，两张图也不能相减得到直连流量。控制器读取失败时显示原因与断点，不填充为零；正常空连接快照连续采样后才显示零速率。

后台、开关切换、失败恢复均重新建立差分基线；更换出口或重新配置控制器重置转发历史和累计。图表横向为最近 60 次采样，控制器响应慢时不等同于一分钟；重建 Activity 后图表与累计重新开始，控制器 Secret 仍需重新输入。

仅在前台采样；后台和暂停恢复重建差分基线，计数回退、无效值和超过 5 秒的缺口不计算速率。VPN 场景下系统接口累计可能重复计量，不等同套餐账单。应用系统统计可能延迟；共享 UID 合并，包可见性限制可能导致名称未知。VPN 开启状态不代表代理可用或出口已验证。

控制器只发送 GET，不切换节点、关闭连接或修改配置。明文 HTTP 仅允许回环地址，远程地址必须 HTTPS 且使用系统证书验证。鉴权 Secret 仅在 Activity 内存中保存，不进入磁盘、日志或命令行；重建 Activity 后需重新输入。失败清空连接，取消、后台或重连后忽略旧请求结果。

手机没有开放控制器时仍可使用概览和诊断；应用排行需要使用情况访问权限。桌面的 Rust SQLite 历史诊断、DSH / Codex 日志接入尚未移植。

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
- Kotlin 标准库 2.2.20：[JetBrains/kotlin](https://github.com/JetBrains/kotlin/tree/v2.2.20)，Apache-2.0。随 APK 打包，`assets/NOTICE.txt` 和 `assets/LICENSE-Apache-2.0.txt` 包含来源与许可。
- AGP 8.13.2：Google 官方 Android 构建插件，Apache-2.0；编译、资源处理、DEX 和 debug 签名，无自定义安装脚本。
- JUnit 4.13.2：EPL-1.0，仅本地单元测试，不打入 APK。

API 口径核对：[TrafficStats](https://developer.android.com/reference/android/net/TrafficStats)、[NetworkStatsManager](https://developer.android.com/reference/android/app/usage/NetworkStatsManager)。
