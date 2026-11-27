# 打包与发布

`Package` workflow 对 PR、main、codex 分支、版本 tag 和手动运行执行测试与 release 构建。Windows 产物包含桌面和 CLI；Android 产物为非 debuggable 的 unsigned release APK。CI artifact 明确标为 unsigned，不能直接当作已签名发行包发布。

Rust 固定 1.97.1，并使用两个 Cargo.lock；Android 沿用 Gradle Wrapper 8.14、AGP 8.13.2、Kotlin 2.2.20 及依赖锁。GitHub 官方 checkout、setup-java 和 upload-artifact 负责检出、JDK 安装和 artifact 上传，无新增应用依赖。

桌面启用 `tauri/custom-protocol`，使原生 cargo release 构建嵌入 UI。Windows 为便携 ZIP，不含安装器，目标机器需已有 WebView2 Runtime。

## 发布顺序

1. 修改版本并通过 `python scripts/check-release-version.py`，功能分支提交 PR，完成 Review 和 CI 后正常合并。
2. 选择以下任一种签名路径（二者执行一致的签名验证基线，输出相同的 `signature-report.json` 与公开 `.cer` 证书）：
   - **路径 A：GitHub Actions 隔离签名工作流（`.github/workflows/sign-release.yml`）**
     - 在仓库 **Settings -> Environments** 创建受保护环境 `release-signing`（建议启用 Required reviewers 人工审批门禁），并按需配置环境级 Secret：
       - Windows：`WINDOWS_SIGN_PFX_BASE64`（可导出代码签名 `.pfx` 的 Base64）、`WINDOWS_SIGN_PFX_PASSWORD`（PFX 口令）。
       - Android：`ANDROID_KEYSTORE_BASE64`（`release.p12` / `.jks` 的 Base64）、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`（可选，默认 `wiesler-release`）、`ANDROID_KEY_PASSWORD`（可选，默认同 keystore 密码）。
     - 手动触发 **Sign and Publish Release**（`workflow_dispatch`），选择目标分支或已有 tag，按需勾选 `sign_windows`、`sign_android`、`allow_self_signed_windows`、`publish_release`、`prerelease`。
     - 构建任务（`build-windows` / `build-android`）不接入 `release-signing` 环境，无法读取密钥；签名任务（`sign-windows` / `sign-android`）在独立 job 中完成签名与校验并立即清理内存/临时证书，随后由 `publish-signed-release` 生成 `signature-report.json`、`SHA256SUMS.txt` 与 Sigstore provenance attestation（`actions/attest-build-provenance@v4`），并在勾选 `publish_release` 时创建或更新 GitHub Release。
   - **路径 B：本机离线签名（`scripts/sign-release.ps1`）**
     - 当 Windows 证书不可导出（如本机 `CurrentUser\My` 中的不可导出证书）或不希望将密钥托管至 GitHub Secrets 时，从合并后的 `main` 成功运行下载 `Package` 的两个 unsigned artifact，记录运行 URL 和源码提交。不要混用不同提交的产物。
     - 在持有私钥的 Windows 机器运行 `scripts/sign-release.ps1` 签名和验证（版本号自动读取 `Cargo.toml`，也可通过 `-Version` 指定）。私钥与密码留在本机用户私有目录，不进入 CI artifact、源码或日志。
     - 检查签名报告、ZIP 内容、CLI 版本和桌面启动；给同一提交打版本 tag，上传 ZIP、APK、公开证书和签名报告。

签名密钥绝不放进源码、日志、命令行参数或 CI artifact：`scripts/sign-windows-ci.ps1` 在内存中解包 PFX 导入临时用户库并用 `/sha1` 签名，结束后删除证书与私钥容器；Android 签名全程通过 `env:` / `-storepass:env` 传参并在结束时清除临时 keystore。后续也可接入 Azure Artifact Signing 等云 HSM 签名服务。

Windows 自签名不等于系统默认信任。验证必须检查文件摘要和签名者；仅允许预期自签名的“不受信任根”状态，其他错误阻断发布。不得通过安装根证书或关闭 SmartScreen 来伪造验证成功。

Android 新发布密钥与此前 debug 签名不同，不能覆盖已有同包名 debug 安装；卸载旧应用会删除其本地数据，需由使用者自行选择。后续版本继续使用同一发布密钥且递增 versionCode。

构建机制核对：[Tauri](https://github.com/tauri-apps/tauri-action)、[Windows 签名文档](https://v2.tauri.app/distribute/sign/windows/)、[Android 签名文档](https://developer.android.com/studio/publish/app-signing)。脚本为本项目编写，未复制第三方实现。
