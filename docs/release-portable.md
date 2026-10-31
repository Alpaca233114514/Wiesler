# Wiesler 0.1.0 测试版 · Windows x64

解压整个目录后运行 `wiesler-desktop.exe`。需要 Windows 10/11 x64 和 Microsoft Edge WebView2 Runtime。命令行入口为 `wiesler.exe --help`。

桌面连接本机 Mihomo HTTP External Controller；需要鉴权时，通过启动环境提供 `WIESLER_MIHOMO_SECRET`，不要放进命令行参数。SQLite 历史诊断使用 CLI 采集的数据。

此测试版使用 Wiesler 本机自签名证书，Windows 默认不信任该证书，可能显示未知发布者或 SmartScreen 提示。不要关闭系统防护；公开证书仅供核对发布者，程序不会自动安装受信任根证书。

这是首个预发布版本。已有短时桌面和 Android 实机验证；长期稳定性、全设备兼容性及断流根因判断尚未完成验证。详见仓库中的使用说明与验收记录。
