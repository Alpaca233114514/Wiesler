use std::{
    io::{Read, Write},
    net::TcpListener,
    sync::{Arc, Mutex},
    time::Duration,
};
use wiesler::{
    config::ControllerConfig,
    probes::mihomo::{Mihomo, ProbeError},
};

fn server(
    responses: Vec<(u64, String)>,
) -> (String, Arc<Mutex<Vec<String>>>, std::thread::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").expect("local test server bind");
    let endpoint = format!("http://{}", listener.local_addr().unwrap());
    let requests = Arc::new(Mutex::new(vec![]));
    let recorded = requests.clone();
    let worker = std::thread::spawn(move || {
        for (delay, response) in responses {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(3)))
                .unwrap();
            let mut request = vec![];
            let mut byte = [0];
            while !request.ends_with(b"\r\n\r\n") {
                if stream.read(&mut byte).unwrap_or(0) == 0 {
                    break;
                }
                request.push(byte[0]);
            }
            recorded
                .lock()
                .unwrap()
                .push(String::from_utf8_lossy(&request).into());
            std::thread::sleep(Duration::from_millis(delay));
            let _ = stream.write_all(response.as_bytes());
        }
    });
    (endpoint, requests, worker)
}

fn response(status: u16, body: &str) -> String {
    format!(
        "HTTP/1.1 {status} Test\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    )
}

#[tokio::test]
async fn http_gets_use_bearer_header_and_parse_actual_wire_shape() {
    let (endpoint, requests, worker) = server(vec![
        (0, response(200, r#"{"version":"synthetic-v1"}"#)),
        (0, response(200, include_str!("fixtures/snapshot.json"))),
    ]);
    let probe = Mihomo::new(
        ControllerConfig::new(&endpoint, Some("test-only-secret"), Duration::from_secs(2)).unwrap(),
    )
    .unwrap();
    assert_eq!(probe.version().await.unwrap(), "synthetic-v1");
    assert_eq!(probe.snapshot().await.unwrap().connections.len(), 1);
    worker.join().unwrap();
    let requests = requests.lock().unwrap();
    assert!(requests[0].starts_with("GET /version HTTP/1.1"));
    assert!(requests[1].starts_with("GET /connections HTTP/1.1"));
    assert!(requests.iter().all(|r| {
        r.to_lowercase()
            .contains("authorization: bearer test-only-secret\r\n")
    }));
    assert!(
        requests
            .iter()
            .all(|r| !r.lines().next().unwrap().contains("secret"))
    );
}

#[tokio::test]
async fn authentication_errors_bad_json_and_http_failures_are_distinct() {
    for (status, body, expected) in [
        (401, "private response", ProbeError::Authentication),
        (403, "private response", ProbeError::Authentication),
        (500, "private response", ProbeError::Http(500)),
        (200, "{", ProbeError::InvalidSnapshot),
        (
            200,
            r#"{"downloadTotal":0,"uploadTotal":0}"#,
            ProbeError::InvalidSnapshot,
        ),
    ] {
        let (endpoint, _, worker) = server(vec![(0, response(status, body))]);
        let probe =
            Mihomo::new(ControllerConfig::new(&endpoint, None, Duration::from_secs(2)).unwrap())
                .unwrap();
        assert_eq!(probe.snapshot().await.unwrap_err(), expected);
        worker.join().unwrap();
    }
}

#[tokio::test]
async fn redirects_are_not_followed_and_credentials_are_not_forwarded() {
    let target = TcpListener::bind("127.0.0.1:0").unwrap();
    target.set_nonblocking(true).unwrap();
    let redirect = format!(
        "HTTP/1.1 302 Found\r\nLocation: http://{}/leak\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
        target.local_addr().unwrap()
    );
    let (endpoint, _, worker) = server(vec![(0, redirect)]);
    let probe = Mihomo::new(
        ControllerConfig::new(&endpoint, Some("synthetic-secret"), Duration::from_secs(2)).unwrap(),
    )
    .unwrap();
    assert_eq!(probe.version().await.unwrap_err(), ProbeError::Http(302));
    worker.join().unwrap();
    assert!(target.accept().is_err());
}

#[tokio::test]
async fn timeout_covers_slow_response() {
    let (endpoint, _, worker) = server(vec![(150, response(200, r#"{"version":"v1"}"#))]);
    let probe =
        Mihomo::new(ControllerConfig::new(&endpoint, None, Duration::from_millis(30)).unwrap())
            .unwrap();
    assert_eq!(probe.version().await.unwrap_err(), ProbeError::Timeout);
    worker.join().unwrap();
}
