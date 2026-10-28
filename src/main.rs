use clap::{CommandFactory, Parser};

#[derive(Parser)]
#[command(
    version,
    about = "Wiesler — 本地网络与代理可观测性工具",
    after_help = "当前为开发骨架，尚未实现网络采集。实现规划见 docs/implementation-report.md。"
)]
struct Cli {}

fn main() -> anyhow::Result<()> {
    Cli::parse();
    Cli::command().print_help()?;
    println!();
    Ok(())
}
