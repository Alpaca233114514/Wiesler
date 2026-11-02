# 桌面 UI 统一化

参照 [Papyrus Desktop 的 theme.css](https://github.com/LiYuanStudio/Papyrus_Desktop/blob/main/frontend/src/theme.css) 和 Sidebar.css，于 2026-11-03 核对 GitHub 当前源码。暖灰画布、蓝色主色、6 / 10 / 14px 圆角及双层阴影由共享的 `desktop/ui/theme.css` 管理，许可见 `desktop/THIRD_PARTY_NOTICES.md`。

覆盖工作台图谱、运行监测、历史诊断、隐私检查、设置、旧版入口与窗口控制。宽屏为侧栏，1000px 以下使用横向导航；浅色 / 深色跟随系统。状态文字与图表颜色作对比度适配，下载绿色、上传蓝色保持可区分。保留采集、诊断与隐私检查逻辑及当前工作区已有修改；未增加依赖。Android 不在本轮范围内。

第一轮验证：现有前端测试 56 项通过，主题变量引用、样式文件链接及括号完整性检查通过，`git diff --check` 通过。当时浏览器工具拒绝本地 file URL，未进行截图或真实 WebView2 验收。

## 2026-11-03 实际窗口验收

- 使用 `cargo build --manifest-path desktop/Cargo.toml --locked --offline --target-dir "$env:LOCALAPPDATA/Wiesler/build"` 成功构建 dev EXE（非 release 安装包）。构建时间为北京时间 07:42，文件大小 18,776,576 字节。
- 输出入口：`C:\Users\Logan\AppData\Local\Wiesler\build\debug\wiesler-desktop.exe`。本次 Windows 应用目录重定向后，实际进程位于 `C:\Users\Logan\AppData\Local\Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Local\Wiesler\build\debug\wiesler-desktop.exe`；两个入口读取到相同构建时间与大小。
- 实际 WebView2 窗口显示新主题；系统深色模式下逐页检查图谱、运行监测、历史诊断、隐私检查和设置。页面切换、卡片、表单、图谱及检查器可见，未发现明显遮挡。实时 Mihomo 与系统连接持续更新。
- 最大化和还原成功，已返回实时图谱并保留窗口。尝试调整窄窗口未形成有效验收；浅色模式、不同 DPI、窄窗口布局和旧版入口仍未实机验证。此次是 UI 验收，不代表所有诊断功能或网络结果均经过验收。
- 重新签名失败：原 DigiCert HTTP 时间戳服务不可达或返回无效响应，HTTPS 重试被 SignTool 判定为无效时间戳 URL。检查产物状态为 `NotSigned`。未修改系统信任或安全设置，未发布安装包。

## 移除桌面演示模式

移除顶栏数据来源切换、模拟连接生成器、播放定时器和演示报告分支，桌面仅采集实时数据；冻结与快照回放仍可使用。测试样本独立放在 `desktop/tests/snapshot-fixture.cjs`，不在打包的 `desktop/ui` 目录中。

验证：55 项前端测试、47 项核心 Rust 测试、8 项桌面 Rust 测试通过，3 项依赖外部环境的 Rust 测试按原设置跳过。根项目和桌面项目的格式及 Clippy（`-D warnings`）检查通过。重新构建 dev EXE 并重开真实窗口，截图与无障碍控件树确认数据来源切换已消失，窗口按钮仍靠右，实时 Mihomo 数据正常显示。本次未重新签名。
