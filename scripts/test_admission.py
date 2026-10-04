#!/usr/bin/env python3
"""Real pinned Hoori HTTP check: bounded admission, pre-codec rejection and recovery."""
from concurrent.futures import ThreadPoolExecutor
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


def main():
    runtime = ROOT / ".docker-context/runtime"
    receipt = verify(runtime)
    calls, snapshots = [], []
    incoming_bound = 1
    incoming_run = None
    hold, release = threading.Event(), threading.Event()

    class Peer(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def reply(self):
            body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            status = 200
            if not self.path.startswith("/v2/"):
                calls.append(self.headers.get("X-Hoori-Endpoint"))
                if hold.is_set():
                    release.wait(5)
            else:
                url = f"http://127.0.0.1:{self.server.server_port}"
                body = json.dumps({"epoch": "admission-probe", "revision": 1, "complete": True,
                    "instances": [{"id": name, "service": name, "version": 1, "url": url,
                        "endpoints": [{"method": "POST", "path": "/echo", "consumes": "application/json", "produces": "application/json"},
                            {"method": "POST", "path": "/public/{id}" if name == "recipes" else "/billing-public/{id}",
                             "consumes": "application/json", "produces": "application/json",
                             "permission": name + ":read"}]} for name in ("recipes", "billing")]}).encode()
                if self.command == "DELETE":
                    status, body = 204, b""
            try:
                self.send_response(status)
                for key, value in {"Content-Type": "application/json", "Content-Length": str(len(body)),
                    "X-Hoori-Catalog-Protocol": "4", "X-Hoori-Catalog-Epoch": "admission-probe",
                    "X-Hoori-Catalog-Revision": "1", "X-Hoori-Catalog-View": self.headers.get("X-Hoori-Catalog-View", "all")}.items():
                    self.send_header(key, value)
                self.end_headers()
                self.wfile.write(body)
            except OSError:
                pass  # Timed-out/cancelled sockets belong to the SDK owner.

        do_PUT = do_POST = do_GET = do_DELETE = reply

        def log_message(self, *args):
            pass

    with socket.socket() as available:
        available.bind(("127.0.0.1", 0))
        port = available.getsockname()[1]

    def request(path, body=None, headers=None):
        connection = HTTPConnection("127.0.0.1", port, timeout=10)
        try:
            fields = {"Content-Type": "application/json"} if body is not None else {}
            fields.update(headers or {})
            connection.request("GET" if body is None else "POST", path, body, fields)
            response = connection.getresponse()
            return response.status, response.read()
        finally:
            connection.close()

    def stats():
        status, body = request("/probe")
        assert status == 200
        snapshot = json.loads(body)
        assert snapshot["incoming_active"] <= incoming_bound and snapshot["incoming_pending"] == 0
        assert snapshot["outgoing_active"] <= 1 and snapshot["outgoing_pending"] <= 1
        assert snapshot["sdk_pending"] == 0
        return snapshot

    def until(test):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            assert process.poll() is None, "Native service exited"
            try:
                snapshot = stats()
                if test(snapshot):
                    snapshots.append(snapshot)
                    return snapshot
            except OSError:
                pass
            time.sleep(.02)
        raise AssertionError("Native state did not reach the expected bound")

    with ThreadingHTTPServer(("127.0.0.1", 0), Peer) as peer:
        server = threading.Thread(target=peer.serve_forever)
        server.start()
        env = os.environ.copy()
        env.update({"HOORI_BIND_ADDRESS": "127.0.0.1", "HOORI_PORT": str(port),
                    "HOORI_REGISTRY_URL": f"http://127.0.0.1:{peer.server_port}",
                    "HOORI_ADVERTISE_URL": f"http://127.0.0.1:{port}", "HOORI_HEARTBEAT_MS": "100",
                    "HOORI_INCOMING_CALLS": "1", "HOORI_INCOMING_PENDING_CALLS": "0",
                    "HOORI_OUTGOING_CALLS": "1", "HOORI_OUTGOING_PENDING_CALLS": "1",
                    "HOORI_CLIENT_CONNECTIONS": "1", "HOORI_CLIENT_PER_ORIGIN": "1",
                    "HOORI_CLIENT_PENDING_ACQUIRES": "0", "HOORI_CLIENT_TIMEOUT_MS": "2000",
                    "HOORI_WORK_TIMEOUT_MS": "10000", "HOORI_REQUEST_TIMEOUT_MS": "15000"})
        cp = ":".join([str(ROOT / "framework/target/test-classes"), str(ROOT / "framework/target/classes")]
                      + list(map(str, runtime_classpath(runtime, receipt))))
        engine = env.get("HOORI_ENGINE", "mixed")
        command = [str(runtime / "bin/hoori"), "run", "--engine", engine, "--live-output", "--graceful-signals",
                   "--max-heap-bytes", "33554432", "--allow-environment-read", "--allow-network-listen",
                   "--allow-network-connect", "--class-path", cp, "hoori/micro/AdmissionMain"]
        process = subprocess.Popen(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            until(lambda s: s["instances"] == 2)
            invoke = {"X-Hoori-Endpoint": "POST /in application/json application/json", "X-Hoori-Version": "1", "Content-Type": "application/json"}
            with ThreadPoolExecutor(max_workers=2) as executor:
                incoming = executor.submit(request, "/in", b'"hold"', invoke)
                before = until(lambda s: s["incoming_active"] == 1 and s["handlers"] == 1)
                assert request("/in", b"{", invoke)[0] == 503
                assert stats()["reads"] == before["reads"], "Rejected input reached the DTO codec"
                assert request("/health/ready")[0] == 200
                assert incoming.result()[0] == 200
                assert request("/in", b"{", invoke)[0] == 400
                assert request("/in", b'"ok"', invoke)[0] == 200
                until(lambda s: s["incoming_active"] == 0)
                process.send_signal(signal.SIGTERM)
                output, errors = process.communicate(timeout=10)
                assert process.returncode == 0 and "admission_closed=true pools_closed=true" in output
                incoming_run = {"pid": process.pid, "stdout": output, "stderr": errors}
                # Ordinary routes now share incoming admission. Give the outgoing-queue probes
                # enough root slots; the first process above separately proved the one-root bound.
                incoming_bound = 8
                env["HOORI_INCOMING_CALLS"] = str(incoming_bound)
                process = subprocess.Popen(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                until(lambda s: s["instances"] == 2)

                for _ in range(2):
                    release.clear()
                    hold.set()
                    count = len(calls)
                    active = executor.submit(request, "/typed", b"{}")
                    before = until(lambda s: s["outgoing_active"] == 1 and len(calls) == count + 1)
                    pending = executor.submit(request, "/json", b"{}")
                    until(lambda s: s["outgoing_pending"] == 1)
                    assert request("/billing", b"{}")[0] == 503
                    assert request("/public/1", b"{")[0] == 503, "Gateway parsed rejected parameters"
                    assert stats()["writes"] == before["writes"] and len(calls) == count + 1
                    hold.clear()
                    release.set()
                    assert active.result()[0] == pending.result()[0] == 200
                    until(lambda s: s["outgoing_active"] == s["outgoing_pending"] == 0)

                count, writes = len(calls), stats()["writes"]
                encoding = executor.submit(request, "/encode", b"{}")
                until(lambda s: s["outgoing_active"] == 1 and s["writes"] == writes + 1)
                expired = executor.submit(request, "/json", b"{}")
                until(lambda s: s["outgoing_pending"] == 1)
                assert expired.result()[0] == encoding.result()[0] == 504
                assert len(calls) == count and stats()["writes"] == writes + 1, "Expired work reached encoding/network"
                until(lambda s: s["outgoing_active"] == s["outgoing_pending"] == 0)
                assert request("/billing", b"{}")[0] == 200
                gateway = request("/public/1", b"{}")
                assert gateway[0] == 200, gateway  # One permit, including gateway encoding.

                release.clear()
                hold.set()
                count = len(calls)
                active = executor.submit(request, "/typed", b"{}")
                until(lambda s: s["outgoing_active"] == 1 and len(calls) == count + 1)
                pending = executor.submit(request, "/json", b"{}")
                until(lambda s: s["outgoing_pending"] == 1)
                process.send_signal(signal.SIGTERM)
                assert pending.result()[0] == 503
                hold.clear()
                release.set()
                assert active.result()[0] == 200
            output, errors = process.communicate(timeout=10)
            assert process.returncode == 0 and "drained=true" in output, (process.returncode, output, errors)
            assert "admission_closed=true pools_closed=true" in output
            assert len(calls) == count + 1, "Stop admitted the queued call"
            fingerprints = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in (
                ROOT / "framework/target/test-classes/hoori/micro/AdmissionMain.class",
                ROOT / "framework/target/hoori-micro-0.1.0-SNAPSHOT.jar", ROOT / "scripts/test_admission.py")}
            print(json.dumps({"engine": engine, "runtime": receipt, "fingerprints": fingerprints, "pid": process.pid,
                "incoming_run": incoming_run, "snapshots": snapshots, "wire_endpoints": calls, "exit": process.returncode,
                "stdout": output, "stderr": errors}, sort_keys=True))
            print("PASS: native incoming/outgoing bounds, pre-codec/pre-wire rejection, deadlines, repeated recovery and drain")
        finally:
            release.set()
            if process.poll() is None:
                process.send_signal(signal.SIGTERM)
                try:
                    output, errors = process.communicate(timeout=10)
                    print(output, errors)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.communicate()
            peer.shutdown()
            server.join()


if __name__ == "__main__":
    main()
