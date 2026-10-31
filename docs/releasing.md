# 打包与发布

`Package` workflow 对 PR、main、codex 分支、版本 tag 和手动运行执行测试与 release 构建。Windows 产物包含桌面和 CLI；Android 产物为非 debuggable 的 unsigned release APK。CI artifact 明确标为 unsigned，不能直接当作已签名发行包发布。

Rust 固定 1.97.1，并使用两个 Cargo.lock；Android 沿用 Gradle Wrapper 8.14、AGP 8.13.2、Kotlin 2.2.20 及依赖锁。GitHub 官方 checkout、setup-java 和 upload-artifact 负责检出、JDK 安装和 artifact 上传，无新增应用依赖。

桌面启用 `tauri/custom-protocol`，使原生 cargo release 构建嵌入 UI。Windows 为便携 ZIP，不含安装器，目标机器需已有 WebView2 Runtime。

## 发布顺序

1. 修改版本并通过 `python scripts/check-release-version.py`，功能分支提交 PR，完成 Review 和 CI 后正常合并。
2. 从合并后的 main 成功运行下载两个 unsigned artifact，记录运行 URL 和源码提交。不要混用不同提交的产物。
3. 在持有私钥的 Windows 机器用 `scripts/sign-release.ps1` 签名和验证：Windows 使用现有不可导出的 CurrentUser 代码签名证书，Android 使用独立正式发布 keystore。私钥与密码留在本机用户私有目录，不进入 CI artifact、源码或日志。
4. 检查签名报告、ZIP 内容、CLI 版本和桌面启动；给同一提交打 `v0.1.0` 标签，创建 GitHub prerelease，上传 ZIP、APK、公开证书和签名报告。

签名机是有意保留的发布步骤：本机 Windows 证书不可导出，CI 无法使用它。CI 不持有发布密钥，也不会在缺少签名时自动开 Release。后续可另行配置可信代码签名服务。

Windows 自签名不等于系统默认信任。验证必须检查文件摘要和签名者；仅允许预期自签名的“不受信任根”状态，其他错误阻断发布。不得通过安装根证书或关闭 SmartScreen 来伪造验证成功。

Android 新发布密钥与此前 debug 签名不同，不能覆盖已有同包名 debug 安装；卸载旧应用会删除其本地数据，需由使用者自行选择。后续版本继续使用同一发布密钥且递增 versionCode。

构建机制核对：[Tauri](https://github.com/tauri-apps/tauri-action)、[Windows 签名文档](https://v2.tauri.app/distribute/sign/windows/)、[Android 签名文档](https://developer.android.com/studio/publish/app-signing)。脚本为本项目编写，未复制第三方实现。
