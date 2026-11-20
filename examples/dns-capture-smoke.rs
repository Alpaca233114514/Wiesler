use std::{fs::OpenOptions, io::Write, net::ToSocketAddrs, time::Duration};
use wiesler::probes::dns_capture::{Capture, Policy, assess, run_authorized_helper};
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = std::env::args().skip(1).collect::<Vec<_>>();
    if let Some(result) = run_authorized_helper(&args) {
        return result.map_err(Into::into);
    }
    let authorize = args.iter().any(|a| a == "--authorize");
    let probe = args.iter().any(|a| a == "--probe");
    let mut capture = if args.iter().any(|a| a == "--auto") {
        Capture::start_auto(30)?
    } else if authorize {
        Capture::start_authorized(30)?
    } else {
        Capture::start_helper(3)?
    };
    let mut probed = false;
    loop {
        std::thread::sleep(Duration::from_millis(100));
        let snapshot = capture.snapshot();
        if probe && !probed && snapshot.event_state == "active" {
            probed = true;
            // 仅验收选项发起系统解析，不混作用户业务证据。 / Explicit acceptance probe, not user traffic evidence.
            let _ = ("example.com", 443).to_socket_addrs();
        }
        if !snapshot.running {
            break;
        }
    }
    let report = capture.stop();
    let assessment = assess(&report, Policy::ProxyRequired);
    if let Some(i) = args.iter().position(|a| a == "--output") {
        let path = args.get(i + 1).ok_or("missing output path")?;
        let mut file = OpenOptions::new().write(true).create_new(true).open(path)?;
        serde_json::to_writer_pretty(
            &mut file,
            &serde_json::json!({"report":report,"assessment":assessment,"validationProbe":if probed { Some("system resolution of example.com") } else { None }}),
        )?;
        file.write_all(b"\n")?;
    }
    println!(
        "packets={} dns_messages={} windows_events={} packet_state={} event_state={} verdict={}",
        report.coverage.packets,
        report.coverage.dns_messages,
        report.coverage.windows_events,
        report.packet_state,
        report.event_state,
        assessment.status
    );
    for error in &report.errors {
        println!("{error}");
    }
    Ok(())
}
