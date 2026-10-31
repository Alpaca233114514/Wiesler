# Android 首版真机记录

2026-10-31，用户要求原生 Kotlin 移动端、监控手机自己的网络，并通过当前 USB 手机安装预览。随后要求减少界面说明小字，已去掉首页脚注、英文眉题及重复标签，数据口径移至「关于数据来源」弹窗。

## 已完成

- `android/` 独立 Kotlin / Android 原生 Views 工程，保留原 Rust、桌面修改及数据；未提交或推送。
- Android 17 / API 37，设备型号 24117RK2CC，通过 ADB 安装 `io.wiesler.android` 0.1.0，最终修正版安装返回 `Success` 并启动。
- 首页实际显示系统差分流量和趋势，最终截图为下载 23.0 KB/s、上传 22.3 KB/s，当前网络「移动网络 / VPN 已启用」。这些是短时系统接口观察，不是业务请求或套餐流量测量。
- 真机发现热点 `LOCAL_NETWORK` 接口被旧逻辑误判为 Wi-Fi 上网，修复为仅使用 `ConnectivityManager.activeNetwork` 的传输类型；系统核验存在 CELLULAR 默认网络和 CELLULAR|VPN 网络，截图复验正确。
- 用户在手机上操作后，`GET_USAGE_STATS` 查询返回 `allow`。没有使用 adb appops 修改权限，没有修改代理、VPN、手机安全设置。
- 最终启动进程存在，抽查 `AndroidRuntime:E` 最近日志没有崩溃条目。

## 构建与检查

- JDK 21（本机 Android Studio JBR），现有 Android SDK 36，Gradle 8.14、AGP 8.13.2、Kotlin 2.2.20，全程使用已有缓存离线构建。
- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 最终通过。
- 3 项 RateTracker 测试：实际时间差分、重置/暂停、无效计数与长缺口；全部通过。
- Lint：0 errors，3 warnings（备份规则建议、绘图时 Path 分配、中文文案国际化）；没有禁用规则或建立忽略基线。
- SDK 工具提示现有命令行工具理解的 XML 版本较旧，但本次构建成功；没有升级工具或依赖。
- 依赖版本锁定在 `android/app/gradle.lockfile`；APK 内附 Kotlin 来源声明和 Apache-2.0 许可。

## 未验证范围

- 手机拒绝 ADB `input tap`，提示 `INJECT_EVENTS` 权限不足。没有尝试修改安全设置或注入权限。因此不声称完成四页自动交互验收、实际应用排行、暂停按钮及 HTTPS 探测的真机验收。
- 首次 APK 安装曾返回 `INSTALL_FAILED_USER_RESTRICTED`，用户解锁并允许后正常安装。此限制来自手机安装流程，非 Codex Auto Review 拒绝。
- 手机安装 Clash Meta，但本次未接通它的 HTTP 控制器；Mihomo 列表、搜索、详情的实现尚未用真实手机控制器验收，未使用模拟数据冒充。
- 只在前台采样，未做长期运行、后台采集或全设备兼容性测试；桌面 SQLite 历史诊断及 DSH / Codex 日志能力未移植。

## 本地产物

- APK：`android/app/build/outputs/apk/debug/app-debug.apk`
- 最终真机截图：`target/android-acceptance-20261031/overview-corrected.png`
- 先前截图保留作诊断记录；部分含系统通知，不应公开发布。
- 单元测试：`android/app/build/reports/tests/testDebugUnitTest/index.html`
- Lint：`android/app/build/reports/lint-results-debug.html`
