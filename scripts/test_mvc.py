#!/usr/bin/env python3
"""Native generated application bootstrap, CRUD and Micro request ownership checks."""
from concurrent.futures import ThreadPoolExecutor
from http.client import HTTPConnection
from pathlib import Path
import argparse
import hashlib
import json
import os
import signal
import socket
import subprocess
import time

from runtime_check import ROOT, runtime_classpath, verify
from stage import jar


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gc-stress", action="store_true")
    args = parser.parse_args()
    runtime = ROOT / ".docker-context/runtime"
    receipt = verify(runtime)
    engine = os.environ.get("HOORI_ENGINE", "mixed")
    jars = [jar("framework", "hoori-micro"), jar("examples/mvc-crud", "mvc-crud")]
    cp = [*jars, *runtime_classpath(runtime, receipt), ROOT / "examples/mvc-crud/target/test-classes"]
    evidence = {"engine": engine, "gc_stress": args.gc_stress, "runtime": receipt["source"],
                "jars": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in cp if p.is_file()},
                "checks": []}
    processes, logs = [], []
    stamp = f"mvc-{engine}-{'gc' if args.gc_stress else 'normal'}-{os.getpid()}"

    def launch(main_class, extra=None):
        with socket.socket() as available:
            available.bind(("127.0.0.1", 0))
            port = available.getsockname()[1]
        log = (ROOT / f".cache/{stamp}-{len(processes)}.log").open("w")
        logs.append(log)
        env = os.environ | {"PATH": "/nonexistent", "JAVA_HOME": "/nonexistent",
            "HOORI_BIND_ADDRESS": "127.0.0.1", "HOORI_PORT": str(port),
            "HOORI_INSTANCE_ID": "mvc", "HOORI_ADVERTISE_URL": f"http://127.0.0.1:{port}",
            "HOORI_INCOMING_CALLS": "1", "HOORI_INCOMING_PENDING_CALLS": "0",
            "HOORI_WORK_TIMEOUT_MS": "5000", "HOORI_REQUEST_TIMEOUT_MS": "10000", "HOORI_SHUTDOWN_GRACE_MS": "1500"}
        env.update(extra or {})
        command = [str(runtime / "bin/hoori"), "run", "--engine", engine, "--live-output", "--graceful-signals",
            "--max-heap-bytes", "33554432", "--allow-environment-read", "--allow-network-listen", "--allow-resource-read",
            "--allow-network-connect", "--class-path", ":".join(map(str, cp))]
        if args.gc_stress:
            command.append("--gc-stress")
        process = subprocess.Popen(command + [main_class], env=env, stdout=log, stderr=log)
        processes.append(process)
        return process, port, Path(log.name)

    def request(port, method, path, data=None, headers=None):
        connection = HTTPConnection("127.0.0.1", port, timeout=15)
        try:
            if isinstance(data, dict):
                data = json.dumps(data).encode()
            connection.request(method, path, data, {"Content-Type": "application/json", **(headers or {})})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def until(process, log, predicate):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            assert process.poll() is None, log.read_text()
            try:
                if predicate():
                    return
            except (OSError, ConnectionError):
                pass
            time.sleep(.025)
        raise AssertionError("Timed out: " + log.read_text())

    def stop(process, log):
        process.send_signal(signal.SIGTERM)
        assert process.wait(timeout=15) == 0, log.read_text()
        assert "roots_drained=true drained=true" in log.read_text(), log.read_text()

    try:
        failed, _, log = launch("probe/ProbeApplication", {"PROBE_FAIL_START": "true"})
        assert failed.wait(timeout=30) != 0
        text = log.read_text()
        assert text.count("probe_resource_created") == text.count("probe_resource_closed") == 1, text
        assert "service_starting" not in text, text
        evidence["checks"].append("startup failure closes alias resource once before any listener/ready")

        app, port, log = launch("dev/hoori/micro/crud/CrudApplication")
        until(app, log, lambda: request(port, "GET", "/health/ready")[0] == 200)
        status, _, body = request(port, "GET", "/recipes/1")
        assert status == 200 and json.loads(body) == {"id": 1, "title": "Kartoffelsuppe"}, (status, body)
        for method, path, data in [("GET", "/recipes/0", None), ("POST", "/recipes", {"title": " "})]:
            status, _, body = request(port, method, path, data)
            assert status == 400 and json.loads(body)["code"] == "validation_failed", (status, body)
        status, headers, body = request(port, "POST", "/recipes", {"title": "Möhren + Äpfel"})
        assert status == 201 and headers["Location"] == "/recipes/2", (status, headers, body)
        assert json.loads(body) == {"id": 2, "title": "Möhren + Äpfel"}, body
        status, _, body = request(port, "GET", "/recipes?prefix=M%C3%B6hren%20%2B")
        assert status == 200 and len(json.loads(body)) == 1, (status, body)
        assert request(port, "GET", "/recipes?prefix=x&prefix=y")[0] == 400
        assert request(port, "POST", "/recipes", b'{"title":42}')[0] == 400
        assert request(port, "POST", "/recipes", b'{"title":"ok"}', {"Content-Type": "text/plain"})[0] == 415
        assert request(port, "GET", "/recipes", headers={"Accept": "text/plain"})[0] == 406
        status, _, body = request(port, "DELETE", "/recipes/2")
        assert status == 204 and body == b"", (status, body)
        status, _, body = request(port, "GET", "/recipes/2")
        assert status == 404 and json.loads(body) == {"code": "recipe_missing"}, (status, body)
        status, headers, _ = request(port, "PATCH", "/recipes/1")
        assert status == 405 and "GET" in headers["Allow"] and "DELETE" in headers["Allow"], (status, headers)
        assert request(port, "GET", "/missing")[0] == 404
        stop(app, log)
        evidence["checks"].append("generated CRUD: path/query/JSON, defaults, UTF-8, duplicates, validation, 201/Location, 204, advice, 404/405/406/415")

        probe, port, log = launch("probe/ProbeApplication")
        until(probe, log, lambda: request(port, "GET", "/health/ready")[0] == 200)
        text = log.read_text()
        assert all(text.count(event) == 1 for event in ["probe_identity_ok", "probe_work_created", "probe_controller_created"]), text
        with ThreadPoolExecutor(max_workers=1) as executor:
            pending = executor.submit(request, port, "GET", "/probe/hold?ms=800", None, {"X-Request-ID": "mvc-scope"})
            until(probe, log, lambda: "probe_body_returned" in log.read_text())
            assert request(port, "POST", "/probe/input", b"not-json")[0] == 503
            assert request(port, "GET", "/health/ready")[0] == 200
            assert not pending.done(), "Response escaped before child drain"
            status, _, body = pending.result(timeout=10)
            assert status == 200 and json.loads(body) == "mvc-scope", (status, body)
        assert "probe_input_called" not in log.read_text()
        assert request(port, "POST", "/probe/input", {"title": " "})[0] == 400
        assert "probe_input_called" not in log.read_text()
        assert request(port, "POST", "/probe/input", {"title": "ok"})[0] == 200
        for path, expected in [("deadline", 504), ("cleanup", 500), ("broken", 500)]:
            status, _, body = request(port, "GET", "/probe/" + path)
            assert status == expected and b"secret" not in body and b"must_not_mask_framework" not in body, (path, status, body)
        status, _, body = request(port, "GET", "/probe/hold?ms=1", headers={"X-Request-ID": "next-request"})
        assert status == 200 and json.loads(body) == "next-request", (status, body)
        evidence["checks"].append("singleton identity; admission before JSON; validation before work; child context, owner isolation, deadline/cleanup priority and recovery")
        with ThreadPoolExecutor(max_workers=1) as executor:
            before = log.read_text().count("probe_body_returned")
            pending = executor.submit(request, port, "GET", "/probe/hold?ms=500")
            until(probe, log, lambda: log.read_text().count("probe_body_returned") > before)
            probe.send_signal(signal.SIGTERM)
            assert pending.result(timeout=10)[0] == 200
            assert probe.wait(timeout=15) == 0, log.read_text()
        text = log.read_text()
        assert text.count("probe_resource_closed") == 1 and text.rindex("probe_child_finished") < text.index("probe_resource_closed"), text
        assert "roots_drained=true drained=true" in text, text
        evidence["checks"].append("SIGTERM drains the admitted child/response before exactly-once resource close")
    finally:
        for process in processes:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)
        for log in logs:
            log.close()
        evidence["logs"] = [str(Path(log.name).relative_to(ROOT)) for log in logs]
        (ROOT / f".cache/{stamp}.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print(json.dumps(evidence, indent=2))


if __name__ == "__main__":
    main()
