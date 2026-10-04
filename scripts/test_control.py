#!/usr/bin/env python3
"""Native registrar fault check against a deliberately delayed loopback discovery peer."""
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import json
import os
import signal
import socket
import subprocess
import threading
import time

from runtime_check import ROOT, runtime_classpath, verify


def request(port, path):
    connection = HTTPConnection("127.0.0.1", port, timeout=3)
    try:
        connection.request("GET", path)
        response = connection.getresponse()
        body = response.read()
        assert response.status == 200, (path, response.status)
        return body
    finally:
        connection.close()


def main():
    runtime = ROOT / ".docker-context/runtime"
    receipt = verify(runtime)
    events, leases, delay_next = [], [0], threading.Event()
    deleting, delete_release = threading.Event(), threading.Event()

    class Peer(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def handle(self):
            try:
                super().handle()
            except ConnectionResetError:
                pass  # A timed-out reusable socket is deliberately reset by its owner.

        def reply(self):
            self.rfile.read(int(self.headers.get("Content-Length", "0")))
            events.append((self.command, time.monotonic()))
            status, body = 200, b'{"epoch":"fault-check","revision":1,"complete":true,"instances":[]}'
            if self.command == "POST":
                leases[0] += 1
                lease = leases[0]
                if lease in (2, 4, 5):
                    time.sleep(1)
                if lease == 6 or delay_next.is_set():
                    status, body = 404, b"Unknown lease"
            if self.command == "PUT" and delay_next.is_set():
                time.sleep(1)
            if self.command == "DELETE":
                status, body = 204, b""
                deleting.set()
                delete_release.wait(2)
            try:
                self.send_response(status)
                for key, value in {"Content-Type": "application/json", "X-Hoori-Catalog-Protocol": "2",
                                   "X-Hoori-Catalog-Epoch": "fault-check", "X-Hoori-Catalog-Revision": "1",
                                   "X-Hoori-Catalog-View": "none", "Content-Length": str(len(body))}.items():
                    self.send_header(key, value)
                self.end_headers()
                self.wfile.write(body)
            except OSError:
                pass  # The SDK must close its timed-out socket; the delayed peer outlives it.

        do_PUT = do_POST = do_DELETE = reply

        def log_message(self, *args):
            pass

    with socket.socket() as available:
        available.bind(("127.0.0.1", 0))
        port = available.getsockname()[1]
    with ThreadingHTTPServer(("127.0.0.1", 0), Peer) as peer:
        server = threading.Thread(target=peer.serve_forever)
        server.start()
        env = os.environ.copy()
        env.update({"BENCH_ROLE": "recipes", "BENCH_VARIANT": "C", "HOORI_BIND_ADDRESS": "127.0.0.1",
                    "HOORI_PORT": str(port), "HOORI_REGISTRY_URL": f"http://127.0.0.1:{peer.server_port}",
                    "HOORI_ADVERTISE_URL": f"http://127.0.0.1:{port}", "HOORI_INSTANCE_ID": "recipes-control-check",
                    "HOORI_HEARTBEAT_MS": "100", "HOORI_REGISTRY_TTL_MS": "600", "HOORI_CONTROL_TIMEOUT_MS": "300",
                    "HOORI_CLIENT_TIMEOUT_MS": "300"})
        cp = ":".join([str(ROOT / "framework/target/test-classes"), str(ROOT / "framework/target/classes")]
                      + list(map(str, runtime_classpath(runtime, receipt))))
        command = [str(runtime / "bin/hoori"), "run", "--engine", env.get("HOORI_ENGINE", "mixed"),
                   "--live-output", "--graceful-signals", "--max-heap-bytes", "33554432", "--allow-environment-read",
                   "--allow-network-listen", "--allow-network-connect", "--class-path", cp, "hoori/micro/BenchmarkMain"]
        started = time.monotonic()
        process = subprocess.Popen(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 45
            while leases[0] < 8 or sum(method == "PUT" for method, _ in events) < 2:
                assert process.poll() is None, "Native service ended before recovery"
                if any(method == "DELETE" for method, _ in events):
                    assert request(port, "/health/ready") == b"UP"
                    raise AssertionError(("Timeout ended registrar while service stayed ready", events))
                assert time.monotonic() < deadline, events
                time.sleep(.05)
            assert all(method != "DELETE" for method, _ in events), "Timeout ended registrar"
            assert request(port, "/health/ready") == b"UP"
            snapshot = json.loads(request(port, "/bench/runtime"))
            assert snapshot["control_pool_pending_acquires"] == 0
            assert snapshot["data_pool_active_connections"] == snapshot["data_pool_pending_acquires"] == 0
            delay_next.set()
            before = len(events)
            while not any(method == "PUT" for method, _ in events[before:]):
                assert time.monotonic() < deadline
                time.sleep(.01)
            process.send_signal(signal.SIGTERM)
            assert deleting.wait(3), "Registrar skipped best-effort deregistration"
            time.sleep(.15)
            assert process.poll() is None, "Owner abandoned in-flight deregistration"
            delete_release.set()
            output, errors = process.communicate(timeout=10)
            assert process.returncode == 0 and "drained=true" in output, (process.returncode, output, errors)
            assert events[-1][0] == "DELETE", events
            assert "pools_closed=true" in output, output
            posts = [when for method, when in events if method == "POST"]
            puts = [when for method, when in events if method == "PUT"]
            print(json.dumps({"engine": env.get("HOORI_ENGINE", "mixed"), "runtime": receipt,
                              "fixture_sha256": hashlib.sha256((ROOT / "framework/target/test-classes/hoori/micro/BenchmarkMain.class").read_bytes()).hexdigest(),
                              "framework_sha256": hashlib.sha256((ROOT / "framework/target/hoori-micro-0.1.0-SNAPSHOT.jar").read_bytes()).hexdigest(),
                              "probe_sha256": hashlib.sha256((ROOT / "scripts/test_control.py").read_bytes()).hexdigest(),
                              "methods": [method for method, _ in events],
                              "events": [{"method": method, "elapsed_ms": round(1000 * (when - started), 3)} for method, when in events],
                              "recovery_ms_from_last_delayed_lease": round(1000 * (puts[1] - posts[4]), 3),
                              "recovered_snapshot": snapshot, "pid": process.pid,
                              "exit": process.returncode, "stdout": output, "stderr": errors}, sort_keys=True))
            print("PASS: repeated native control timeouts recover; SIGTERM cancels control and closes both pools")
        finally:
            delete_release.set()
            if process.poll() is None:
                process.send_signal(signal.SIGTERM)
                try:
                    process.communicate(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.communicate()
            peer.shutdown()
            server.join()


if __name__ == "__main__":
    main()
