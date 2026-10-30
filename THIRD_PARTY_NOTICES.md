# 来源与许可证

## DSHObserver

- 项目：[Alpaca233114514/better-dsh — DSHObserver](https://github.com/Alpaca233114514/better-dsh/tree/main/DSHObserver)
- 本次依据：用户指定的本机 `better-dsh/DSHObserver`，`package.json` 版本 `0.1.2`，2026-10-30 读取。
- 许可证：Apache License 2.0，原组件自带 `LICENSE`；Wiesler 根目录的 [LICENSE](LICENSE) 保留完整 Apache-2.0 文本。
- 复用范围：`src/application.rs` 将 `core.mjs` 的 usage 输入分桶、decode/end-to-end TPS 计算和 `analysis.mjs` 的成功状态筛选移植为 Rust；`src/diagnostics.rs` 沿用按 provider/model/purpose/reasoning 分组及 token 总数/时长总数的加权口径。
- 修改：适配 Wiesler 类型化事件；仅接受有 usage 的成功 attempt 生成 TPS；不直接相信输入文件中的 TPS 值；不保存请求、推理、输出正文或工具正文。其余采集、存储、日志跟随和诊断实现为本项目新增。

未将 DSHObserver 插件安装、升级或重新加载到用户的 DSH；可直接读取已安装插件产生的 JSONL。原项目现有许可证未列出具体版权姓名，不添加推测性的版权归属。

Mihomo、bandwhich、OpenAI Codex 和 Clash-Verge-CLI 用于接口或环境调研；未复制这些项目的代码。
