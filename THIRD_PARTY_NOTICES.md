# 来源与许可证

## DSHObserver

- 项目：[Alpaca233114514/better-dsh — DSHObserver](https://github.com/Alpaca233114514/better-dsh/tree/main/DSHObserver)
- 本次依据：用户指定的本机 `better-dsh/DSHObserver`，`package.json` 版本 `0.1.2`，2026-10-30 读取。
- 许可证：Apache License 2.0，原组件自带 `LICENSE`；Wiesler 根目录的 [LICENSE](LICENSE) 保留完整 Apache-2.0 文本。
- 复用范围：`src/application.rs` 将 `core.mjs` 的 usage 输入分桶、decode/end-to-end TPS 计算和 `analysis.mjs` 的成功状态筛选移植为 Rust；`src/diagnostics.rs` 沿用按 provider/model/purpose/reasoning 分组及 token 总数/时长总数的加权口径。
- 修改：适配 Wiesler 类型化事件；仅接受有 usage 的成功 attempt 生成 TPS；不直接相信输入文件中的 TPS 值；不保存请求、推理、输出正文或工具正文。其余采集、存储、日志跟随和诊断实现为本项目新增。

未将 DSHObserver 插件安装、升级或重新加载到用户的 DSH；可直接读取已安装插件产生的 JSONL。原项目现有许可证未列出具体版权姓名，不添加推测性的版权归属。

Mihomo、bandwhich、OpenAI Codex 和 Clash-Verge-CLI 用于接口或环境调研；未复制这些项目的代码。

## Papyrus Desktop

`ui/theme.css` adapts the palette, radius and shadow tokens from [Papyrus Desktop](https://github.com/LiYuanStudio/Papyrus_Desktop/blob/main/frontend/src/theme.css), retrieved 2026-11-03. The layout and component styling are implemented for Wiesler. Dark-mode accent and status text colors are adjusted for readability.

MIT License

Copyright (c) 2026 ALPACA LI

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
