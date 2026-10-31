"""Controlled P1 acceptance: loopback mock controller and disposable evidence in target/."""

import argparse
from contextlib import contextmanager
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import sqlite3
import subprocess
import threading
import time


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = json.loads((ROOT / "tests/fixtures/snapshot.json").read_text(encoding="utf-8"))


@contextmanager
def controller(snapshots, unauthorized=False):
    state = {"snapshots": 0, "requests": []}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            state["requests"].append(self.path)
            if unauthorized:
                status, body = 401, b"synthetic authentication failure"
            elif self.path == "/version":
                status, body = 200, b'{"version":"synthetic-v1"}'
            elif self.path == "/connections":
                index = min(state["snapshots"], len(snapshots) - 1)
                state["snapshots"] += 1
                status, body = 200, snapshots[index]
            else:
                status, body = 404, b""
            self.send_response(status)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", state
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=3)


def events(db, session=None):
    with sqlite3.connect(db) as connection:
        if session:
            rows = connection.execute("SELECT json FROM events WHERE session_id=? ORDER BY sequence", (session,))
        else:
            rows = connection.execute("SELECT json FROM events ORDER BY rowid")
        return [json.loads(row[0]) for row in rows]


def wait_for(predicate, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if predicate():
                return
        except (sqlite3.OperationalError, FileNotFoundError):
            pass
        time.sleep(0.02)
    raise AssertionError("timed out waiting for controlled test state")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", type=Path, default=ROOT / "target/debug/wiesler.exe")
    args = parser.parse_args()
    binary = args.binary.resolve()
    output = ROOT / "target" / "p1-acceptance" / str(time.time_ns())
    output.mkdir(parents=True)
    checks = []

    def run(*arguments, expected=0):
        result = subprocess.run([str(binary), *map(str, arguments)], capture_output=True, text=True, encoding="utf-8", timeout=20)
        assert result.returncode == expected, (arguments, result.returncode, result.stderr)
        return result

    def record_arguments(endpoint, db, samples=None, *extra):
        arguments = ["record", "--controller", endpoint, "--db", str(db), "--interval-ms", "20", "--timeout-ms", "300"]
        if samples is not None:
            arguments.extend(["--samples", str(samples)])
        return arguments + list(extra)

    raw = json.dumps(FIXTURE).encode()
    recovered = json.loads(raw)
    recovered["connections"][0]["download"] += 10
    recovered["downloadTotal"] += 10
    empty = dict(recovered, connections=[])
    db = output / "recovery.sqlite3"
    with controller([raw, b"{bad json", json.dumps(recovered).encode(), json.dumps(empty).encode()]) as (endpoint, state):
        run(*record_arguments(endpoint, db, 4))
        assert state["snapshots"] == 4
    session = json.loads(run("sessions", "--db", db).stdout)[0]
    assert session["status"] == "completed_with_gaps"
    history = events(db)
    assert sum(e["kind"] == "ConnectionObserved" for e in history) == 2
    assert sum(e["kind"] == "ConnectionDisappeared" for e in history) == 1
    gap = next(e for e in history if e["kind"] == "ObservationGap")
    assert gap["payload"]["failures"] == 1
    ids = set()
    for event in history:
        assert all(reference in ids for reference in event["evidence_ids"])
        ids.add(event["event_id"])
    jsonl = output / "events.jsonl"
    run("export", "--db", db, "--session", session["session_id"], "--output", jsonl)
    exported = jsonl.read_text(encoding="utf-8")
    assert "private" not in exported
    assert [json.loads(line) for line in exported.splitlines()] == history
    run("export", "--db", db, "--session", session["session_id"], "--output", jsonl, expected=1)
    assert jsonl.read_text(encoding="utf-8") == exported
    replay = json.loads(run("replay", "--db", db, "--session", session["session_id"]).stdout)
    assert sorted(c["status"] for c in replay["connections"].values()) == ["disappeared_reason_unknown", "observation_lost"]
    checks.append("HTTP -> gap/recovery -> SQLite -> JSONL -> replay; no fabricated close; export never overwrites")

    with controller([raw]) as (endpoint, _):
        run(*record_arguments(endpoint, db, 1))
    sessions = json.loads(run("sessions", "--db", db).stdout)
    assert len(sessions) == 2 and sessions[0]["session_id"] != sessions[1]["session_id"]
    checks.append("process restart creates isolated session")

    auth_db = output / "auth.sqlite3"
    with controller([], unauthorized=True) as (endpoint, state):
        run(*record_arguments(endpoint, auth_db, 5), expected=1)
        assert state["requests"] == ["/version"]
    assert json.loads(run("sessions", "--db", auth_db).stdout)[0]["status"] == "authentication_failed"
    assert any(e["kind"] == "ControllerUnavailable" for e in events(auth_db))
    checks.append("401 stops retries, preserves health failure and exits nonzero")

    limit_db = output / "limit.sqlite3"
    with controller([raw]) as (endpoint, _):
        run(*record_arguments(endpoint, limit_db, 2, "--max-events", "1"), expected=1)
    limited = json.loads(run("sessions", "--db", limit_db).stdout)[0]
    assert limited["status"] == "incomplete" and limited["event_count"] == 1
    checks.append("storage limit fails closed without deleting evidence or writing partial batch")

    crash_db = output / "crash.sqlite3"
    with controller([raw]) as (endpoint, _):
        child = subprocess.Popen([str(binary), *record_arguments(endpoint, crash_db)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            wait_for(lambda: crash_db.exists() and len(events(crash_db)) >= 4)
            child.kill()
            child.communicate(timeout=10)
        finally:
            if child.poll() is None:
                child.kill()
                child.communicate(timeout=10)
        old = json.loads(run("sessions", "--db", crash_db).stdout)[0]
        assert old["status"] == "recording_or_interrupted" and old["ended_at_utc"] is None
        assert not any(e["kind"] == "ConnectionDisappeared" for e in events(crash_db))
        run(*record_arguments(endpoint, crash_db, 1))
        assert len(json.loads(run("sessions", "--db", crash_db).stdout)) == 2
    checks.append("forced process termination leaves committed WAL evidence readable; subsequent run is isolated")

    pressure_db = output / "pressure.sqlite3"
    with controller([raw, json.dumps(empty).encode(), raw]) as (endpoint, _):
        child = subprocess.Popen([str(binary), *record_arguments(endpoint, pressure_db, 30, "--queue-capacity", "1")], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            wait_for(lambda: pressure_db.exists() and len(events(pressure_db)) >= 4)
            with sqlite3.connect(pressure_db, timeout=3) as connection:
                connection.execute("BEGIN IMMEDIATE")
                time.sleep(1)
                connection.commit()
            stdout, stderr = child.communicate(timeout=10)
            assert child.returncode == 0, (stdout, stderr)
        finally:
            if child.poll() is None:
                child.kill()
                child.communicate(timeout=10)
    pressure = events(pressure_db)
    assert any(e["kind"] == "ObservationGap" and e["payload"]["dropped_samples"] > 0 for e in pressure)
    assert json.loads(run("sessions", "--db", pressure_db).stdout)[0]["status"] == "completed_with_gaps"
    checks.append("bounded queue overflow under SQLite write lock records dropped samples and observation gap")

    invalid_db = output / "must-not-exist.sqlite3"
    run("record", "--controller", "http://192.0.2.1", "--db", invalid_db, expected=1)
    assert not invalid_db.exists()
    checks.append("non-loopback configuration rejected before database creation")

    summary = {"kind": "controlled_mock_acceptance", "checks": checks, "real_mihomo_tested": False}
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": len(checks), "artifacts": str(output), **summary}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
