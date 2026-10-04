#!/usr/bin/env python3
"""Controlled HTTP peers exercise Micro's managed roots and real pinned Hoori transports."""
from concurrent.futures import ThreadPoolExecutor
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import json
import os
from pathlib import Path
import select
import signal
import socket
import subprocess
import threading
import time

from runtime_check import ROOT, runtime_classpath, verify


def main():
    runtime = ROOT / ".docker-context/runtime"
    receipt = verify(runtime)
    engine = os.environ.get("HOORI_ENGINE", "mixed")
    evidence = {"engine": engine, "runtime": receipt["source"], "checks": [], "recovery": []}
    files = [Path(__file__), ROOT / "framework/target/test-classes/hoori/micro/TasksMain.class",
             ROOT / "framework/target/hoori-micro-0.1.0-SNAPSHOT.jar"]
    evidence["sha256"] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    lock = threading.Lock()
    seen, events = [], []
    holds, cleanup, registry = threading.Event(), threading.Event(), threading.Event()
    registry.set()
    batch_active = batch_max = 0

    class Peer(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def handle(self):
            try:
                super().handle()
            except (ConnectionResetError, BrokenPipeError):
                pass  # Cancellation intentionally drops a partially consumed response.

        def reply(self):
            nonlocal batch_active, batch_max
            raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            action = self.headers.get("X-Hoori-Action", "")
            with lock:
                seen.append({"action": action, "id": self.headers.get("X-Request-ID"),
                             "credentials": bool(self.headers.get("Authorization") or self.headers.get("Cookie")),
                             "path": self.path, "time": time.monotonic()})
            status = 200
            if self.path.startswith("/v1/"):
                if not registry.is_set():
                    with lock:
                        events.append("registry-wait")
                    registry.wait(15)
                view = self.headers.get("X-Hoori-Catalog-View", "all")
                instances = [{"id": role, "service": role, "version": 1, "url": f"http://127.0.0.1:{port}",
                              "actions": [{"name": name} for name in ("echo", "hold", "fail", "batch")]}
                             for role, port in peers.items()]
                body = json.dumps({"epoch": "tasks-test", "revision": 1, "complete": True,
                                   "instances": instances}).encode()
                self.send_response(200)
                for k, v in {"X-Hoori-Catalog-Protocol": "2", "X-Hoori-Catalog-Epoch": "tasks-test",
                             "X-Hoori-Catalog-Revision": "1", "X-Hoori-Catalog-View": view}.items():
                    self.send_header(k, str(v))
            else:
                if self.path in ("/cleanup", "/closed", "/resource", "/noncooperative"):
                    with lock:
                        events.append(self.path[1:])
                    if self.path == "/cleanup":
                        cleanup.wait(15)
                if action.endswith(".fail"):
                    status = 409
                body = json.dumps({"id": self.headers.get("X-Request-ID"), "action": action,
                                   "input": json.loads(raw) if raw else None,
                                   "detail": "SECRET upstream detail" if status != 200 else "ok"}).encode()
                self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            try:
                if action.endswith((".hold", ".batch")):
                    gate = holds
                    if action.endswith(".batch"):
                        with lock:
                            batch_active += 1
                            batch_max = max(batch_max, batch_active)
                    self.wfile.write(body[:1])
                    self.wfile.flush()
                    with lock:
                        events.append("body:" + action)
                    while not gate.wait(.005):
                        if select.select([self.connection], [], [], 0)[0] and not self.connection.recv(1, socket.MSG_PEEK):
                            with lock:
                                events.append("cancelled:" + action)
                            return
                    if action.endswith(".batch"):
                        with lock:
                            batch_active -= 1
                    self.wfile.write(body[1:])
                else:
                    self.wfile.write(body)
            except OSError:
                pass

        do_GET = do_POST = do_PUT = do_DELETE = reply

        def log_message(self, *args):
            pass

    servers = [ThreadingHTTPServer(("127.0.0.1", 0), Peer) for _ in range(2)]
    peers = dict(zip(("peer", "other"), (server.server_port for server in servers)))
    for server in servers:
        threading.Thread(target=server.serve_forever, daemon=True).start()
    proc, log = None, None
    port = 0

    def request(path, payload=None, headers=None, connection=None):
        own = connection is None
        connection = connection or HTTPConnection("127.0.0.1", port, timeout=12)
        try:
            connection.request("POST" if payload is not None else "GET", path, payload, headers or {})
            response = connection.getresponse()
            return response.status, response.read()
        finally:
            if own:
                connection.close()

    def until(check, label):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            assert proc.poll() is None, f"Native process exited during {label}"
            try:
                if check():
                    return
            except (OSError, ConnectionError):
                pass
            time.sleep(.01)
        raise AssertionError(f"Timed out: {label}; events={events}")

    def state():
        status, body = request("/control/state")
        assert status == 200, (status, body)
        return json.loads(body)

    def idle():
        until(lambda: all(state()[key] == 0 for key in ("roots", "incoming", "outgoing", "pending", "poolPending", "poolActive")), "resource recovery")
        assert not state()["diagnostics"]

    def reset():
        nonlocal holds, cleanup
        holds.set()
        cleanup.set()
        idle()
        holds, cleanup = threading.Event(), threading.Event()
        with lock:
            events.clear()
            seen.clear()

    def start(label, incoming=8, occupied_port=None, startup_race=False):
        nonlocal proc, port, log
        with socket.socket() as available:
            available.bind(("127.0.0.1", 0))
            port = available.getsockname()[1]
        if occupied_port is not None:
            port = occupied_port
        env = os.environ | {"HOORI_BIND_ADDRESS": "127.0.0.1", "HOORI_PORT": str(port),
            "HOORI_REGISTRY_URL": f"http://127.0.0.1:{peers['peer']}", "TASK_PEER": f"http://127.0.0.1:{peers['peer']}",
            "HOORI_HEARTBEAT_MS": "100", "HOORI_REGISTRY_TTL_MS": "3000", "HOORI_CONTROL_TIMEOUT_MS": "300", "HOORI_CLIENT_IDLE_MS": "100",
            "HOORI_CATALOG_MAX_AGE_MS": "30000", "HOORI_BODY_BYTES": "8388608", "HOORI_REQUEST_TIMEOUT_MS": "10000", "HOORI_WORK_TIMEOUT_MS": "8000",
            "HOORI_CLIENT_TIMEOUT_MS": "6000", "HOORI_CLIENT_CONNECTIONS": "4", "HOORI_CLIENT_PER_ORIGIN": "1",
            "HOORI_CLIENT_PENDING_ACQUIRES": "4", "HOORI_OUTGOING_CALLS": "4", "HOORI_OUTGOING_PENDING_CALLS": "2",
            "HOORI_INCOMING_CALLS": str(incoming), "HOORI_INCOMING_PENDING_CALLS": "0", "HOORI_SHUTDOWN_GRACE_MS": "400"}
        if occupied_port is not None:
            env["TASK_START_FAIL"] = "1"
        if startup_race:
            env["TASK_START_RACE"] = "1"
        cp = [ROOT / "framework/target/test-classes", ROOT / "framework/target/classes"] + runtime_classpath(runtime, receipt)
        log = (ROOT / f".cache/tasks-{engine}-{label}.log").open("w")
        proc = subprocess.Popen([str(runtime / "bin/hoori"), "run", "--engine", engine, "--live-output", "--graceful-signals",
            "--max-heap-bytes", "33554432", "--allow-environment-read", "--allow-network-listen", "--allow-network-connect",
            "--class-path", ":".join(map(str, cp)), "hoori/micro/TasksMain"], env=env, stdout=log, stderr=log)
        if occupied_port is None and not startup_race:
            until(lambda: request("/health/ready")[0] == 200 and request("/call/echo")[0] == 200, "startup and discovery")

    def stop():
        holds.set()
        cleanup.set()
        registry.set()
        if proc.poll() is None:
            proc.send_signal(signal.SIGTERM)
        assert proc.wait(timeout=10) == 0
        log.close()
        assert "tasks_closed=true" in Path(log.name).read_text()
        assert "SECRET" not in Path(log.name).read_text(), "Unsafe error detail was logged"

    try:
        start("requests")
        with ThreadPoolExecutor(max_workers=8) as pool:
            connection = HTTPConnection("127.0.0.1", port, timeout=12)
            for name in ("first", "second"):
                status, body = request("/metadata", b"private-body", {"X-Request-ID": name, "Authorization": "SECRET"}, connection)
                result = json.loads(body)
                assert status == 200 and result["owner"] == result["child"] == result["remote"]["id"] == name, result
            connection.close()
            futures = [pool.submit(request, "/metadata", b"body", {"X-Request-ID": f"overlap-{i}"}) for i in range(4)]
            for i, future in enumerate(futures):
                status, body = future.result()
                assert status == 200 and json.loads(body)["remote"]["id"] == f"overlap-{i}", (status, body)
            assert all(not item["credentials"] for item in seen)
            evidence["checks"].append("keepalive and overlapping invocation isolation, lazy reusable specs, owner-only request")
            reset()
            first = pool.submit(request, "/call/hold")
            until(lambda: "body:peer.hold" in events, "response body parked")
            waiting = pool.submit(request, "/cancel/echo")
            until(lambda: state()["poolPending"] == 1, "pool waiter")
            assert any("READ" in item for item in state()["diagnostics"]), state()
            assert request("/other")[0] == 200
            assert request("/control/cancel", b"")[0] == 200
            assert waiting.result()[0] == 503
            assert not any(item["action"] == "peer.echo" for item in seen)
            assert not first.done()
            holds.set()
            assert first.result()[0] == 200 and request("/call/echo")[0] == 200
            reset()
            cancelled = pool.submit(request, "/cancel/hold")
            until(lambda: "body:peer.hold" in events, "cancel body parked")
            assert request("/other")[0] == 200
            request("/control/cancel", b"")
            assert cancelled.result()[0] == 503
            until(lambda: "cancelled:peer.hold" in events, "actual body socket cancellation")
            assert request("/call/echo")[0] == 200
            evidence["checks"].append("pool and body cancellation preserve concurrent and later same-client calls")
            reset()
            parallel = pool.submit(request, "/parallel")
            until(lambda: all("body:" + name + ".hold" in events for name in peers), "both calls started before release")
            assert not parallel.done()
            holds.set()
            assert parallel.result()[0] == 200
            reset()
            failed = pool.submit(request, "/fail-fast")
            until(lambda: "cleanup" in events, "fail-fast finally gate")
            assert not failed.done() and state()["roots"] == 1 and not state()["childFinished"]
            cleanup.set()
            assert failed.result()[0] == 422
            assert request("/settled")[0] == 200
            for path in ("/deadline", "/settled-deadline"):
                assert request(path)[0] == 504, path
            assert request("/call/hold")[0] == 504
            assert "reason=io_timeout" in Path(log.name).read_text()
            assert "reason=deadline" in Path(log.name).read_text()
            for kind, expected in (("body", 409), ("child", 409), ("cleanup", 500), ("encoder", 500)):
                status, body = request("/error/" + kind)
                assert status == expected and b"SECRET" not in body and b"provisional" not in body, (kind, status, body)
            assert request("/capacity")[0] == 503
            idle()
            evidence["checks"].append("fail-fast primary business error, actual finally drain, settled/global deadline and safe failures")
            reset()
            batch = pool.submit(request, "/batch")
            until(lambda: events.count("body:peer.batch") == 1 and state()["poolPending"] == 1, "bounded batch")
            assert state()["outgoing"] == 2
            holds.set()
            status, body = batch.result()
            assert status == 200 and [item["input"]["index"] for item in json.loads(body)] == list(range(7))
            assert batch_max <= 2
            evidence["checks"].append("bounded batch preserves seven input positions")
            for cycle in range(5):
                reset()
                for i in range(8):
                    assert request("/metadata", b"body", {"X-Request-ID": f"cycle-{cycle}-{i}"})[0] == 200
                assert request("/settled")[0] == 200
                assert request("/deadline")[0] == 504
                idle()
                time.sleep(.3)
                request("/control/gc", b"")
                snapshot = state()
                assert snapshot["retainedRequests"] == 0, snapshot
                evidence["recovery"].append(snapshot)
            samples = evidence["recovery"]
            assert all(s["tasks"] <= samples[0]["tasks"] + 2 and s["handles"] <= samples[0]["handles"] + 2 for s in samples)
            assert all(s["gc"] > 0 for s in samples)
            stop()

            start("drain", incoming=1)
            reset()
            draining = pool.submit(request, "/drain")
            until(lambda: "cleanup" in events, "child cleanup after body return")
            snapshot = state()
            assert snapshot["bodyReturned"] and snapshot["roots"] == snapshot["incoming"] == 1 and not draining.done()
            assert any("DRAIN" in value for value in snapshot["diagnostics"]), snapshot
            assert request("/call/echo")[0] == 503 and request("/health/live")[0] == 200
            cleanup.set()
            assert draining.result()[0] == 200
            assert state()["childFinished"] and state()["resourceClosed"]
            assert request("/call/echo")[0] == 200
            evidence["checks"].append("root and incoming permit retained through child/resource drain; fixed health bypass")
            stop()

            start("grace")
            reset()
            work = pool.submit(request, "/call/hold")
            until(lambda: "body:peer.hold" in events, "grace work")
            proc.send_signal(signal.SIGTERM)
            holds.set()
            assert work.result()[0] == 200
            stop()
            start("shutdown")
            reset()
            work = pool.submit(request, "/shutdown")
            until(lambda: all("body:" + name + ".hold" in events for name in peers), "shutdown fanout")
            registry.clear()
            until(lambda: "registry-wait" in events, "slow registry")
            started = time.monotonic()
            proc.send_signal(signal.SIGTERM)
            until(lambda: "cleanup" in events, "shutdown cancellation and finally")
            evidence["shutdown_cancel_seconds"] = time.monotonic() - started
            assert evidence["shutdown_cancel_seconds"] < 1.3
            assert proc.poll() is None and "closed" not in events and "resource" not in events and not work.done()
            cleanup.set()
            try:
                work.result()
            except (OSError, ConnectionError):
                pass  # The transport grace is already consumed; no promise of a sent error status.
            stop()
            assert events.index("cleanup") < events.index("resource") < events.index("closed"), events
            evidence["checks"].append("SIGTERM grace, cancellation/finally/resource/client order and independent bounded registry")
            start("response-io")
            reset()
            with socket.socket() as stalled:
                stalled.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
                stalled.connect(("127.0.0.1", port))
                stalled.sendall(b"GET /response HTTP/1.1\r\nHost: localhost\r\n\r\n")
                until(lambda: state()["responseBuilt"] and state()["roots"] == 0, "root ended before response I/O")
                started = time.monotonic()
                proc.send_signal(signal.SIGTERM)
                time.sleep(.1)
                assert proc.poll() is None, "Transport lost its remaining grace"
                stop()
                evidence["response_io_grace_seconds"] = time.monotonic() - started
                assert evidence["response_io_grace_seconds"] >= .3
                assert "roots_drained=true drained=false" in Path(log.name).read_text()
            evidence["checks"].append("completed root does not imply response I/O drain; transport uses remaining grace")
            with socket.socket() as occupied:
                occupied.bind(("127.0.0.1", 0))
                occupied.listen(1)
                start("start-failure", occupied_port=occupied.getsockname()[1])
                assert proc.wait(timeout=10) == 0
                log.close()
                assert "start_failed_cleanly=true" in Path(log.name).read_text()
                assert "tasks_closed=true" in Path(log.name).read_text()
            evidence["checks"].append("start failure and repeated close release owned resources")
            start("startup-race", startup_race=True)
            assert proc.wait(timeout=15) == 0
            log.close()
            assert "startup_stop_races=32" in Path(log.name).read_text()
            evidence["checks"].append("32 startup/stop/close interleavings without leaked work or repeated resource close")
            start("handler-stop")
            assert request("/stop")[0] == 200
            stop()
            start("noncooperative")
            reset()
            work = pool.submit(request, "/noncooperative")
            until(lambda: "noncooperative" in events, "noncooperative negative")
            proc.send_signal(signal.SIGTERM)
            time.sleep(.8)
            assert proc.poll() is None and "closed" not in events
            proc.kill()
            proc.wait(timeout=5)
            log.close()
            try:
                work.result()
            except (OSError, ConnectionError):
                pass
            assert "roots_drained=true" not in Path(log.name).read_text()
            evidence["negative"] = "Noncooperating work did NOT drain; harness killed the process after observing it alive beyond grace."
        target = ROOT / f".cache/tasks-{engine}.json"
        target.write_text(json.dumps(evidence, indent=2) + "\n")
        print(f"PASS: managed HTTP/context/cancellation/drain/recovery; evidence {target}")
    finally:
        holds.set()
        cleanup.set()
        registry.set()
        if proc is not None and proc.poll() is None:
            proc.kill()
            proc.wait(timeout=5)
        if log is not None:
            log.close()
        for server in servers:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    main()
