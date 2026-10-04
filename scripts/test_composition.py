#!/usr/bin/env python3
"""Five actual Hoori services: discovery, two gated demo providers, Shopping and Gateway."""
from concurrent.futures import ThreadPoolExecutor
from http.client import HTTPConnection
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import time

from runtime_check import ROOT, runtime_classpath, verify
from stage import jar


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--facade", action="store_true", help="also check the independently built task-facade example")
    args = parser.parse_args()
    runtime = ROOT / ".docker-context/runtime"
    receipt = verify(runtime)
    engine = os.environ.get("HOORI_ENGINE", "mixed")
    modules = [("framework", "hoori-micro"), ("examples/demo-contracts", "hoori-micro-demo-contracts")]
    modules += [(f"examples/{name}-service", f"{name}-service") for name in ("recipes", "pantry", "shopping")]
    jars = [jar(*module) for module in modules]
    cp = [ROOT / "examples/shopping-service/target/test-classes", *jars, *runtime_classpath(runtime, receipt)]
    ports, processes, logs = {}, {}, {}
    roles = ["registry", "recipes", "pantry", "shopping", "gateway"] + (["facade"] if args.facade else [])
    for role in roles:
        with socket.socket() as available:
            available.bind(("127.0.0.1", 0))
            ports[role] = available.getsockname()[1]
    evidence = {"engine": engine, "runtime": receipt["source"], "checks": [],
                "jars": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in jars}}
    if args.facade:
        from optional_example import classpath, configuration
        _, facade_lock = configuration("task-facade")
        verify(runtime, facade_lock)
        facade_cp = classpath("task-facade", runtime, receipt, facade_lock)
        assert all("processor" not in p.name and "annotations" not in p.name for p in facade_cp)
        evidence["facade_classpath"] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in facade_cp}
        facade_cp += [ROOT / "examples/task-facade/target/test-classes"]

    def request(role, path, body=None, headers=None):
        connection = HTTPConnection("127.0.0.1", ports[role], timeout=15)
        try:
            data = None if body is None else json.dumps(body).encode()
            connection.request("GET" if body is None else "POST", path, data,
                               {"Content-Type": "application/json", **(headers or {})})
            response = connection.getresponse()
            return response.status, response.read()
        finally:
            connection.close()

    def until(check, message):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            assert all(p.poll() is None for p in processes.values()), "Service exited before " + message
            try:
                if check():
                    return
            except (OSError, ConnectionError):
                pass
            time.sleep(.01)
        raise AssertionError(message)

    def probe(role):
        status, body = request(role, "/probe")
        assert status == 200, (role, status, body)
        return json.loads(body)

    def gate(role, mode):
        assert request(role, "/gate/" + mode, {})[0] == 200

    def result(role, path, body=None, headers=None):
        status, raw = request(role, path, body, headers)
        assert status == 200, (role, path, status, raw)
        return json.loads(raw)

    def stop(role):
        proc = processes.pop(role)
        proc.send_signal(signal.SIGTERM)
        assert proc.wait(timeout=12) == 0, role
        logs[role].flush()
        assert "roots_drained=true" in Path(logs[role].name).read_text(), role

    try:
        for role in ports:
            env = os.environ | {"HOORI_BIND_ADDRESS": "127.0.0.1", "HOORI_PORT": str(ports[role]),
                "HOORI_REGISTRY_URL": f"http://127.0.0.1:{ports['registry']}", "HOORI_INSTANCE_ID": role,
                "HOORI_ADVERTISE_URL": f"http://127.0.0.1:{ports[role]}", "COMPOSITION_ROLE": role,
                "HOORI_HEARTBEAT_MS": "100", "HOORI_REGISTRY_TTL_MS": "2000", "HOORI_CLIENT_TIMEOUT_MS": "10000",
                "HOORI_WORK_TIMEOUT_MS": "12000", "HOORI_REQUEST_TIMEOUT_MS": "15000", "HOORI_SHUTDOWN_GRACE_MS": "400",
                "HOORI_CLIENT_CONNECTIONS": "4", "HOORI_CLIENT_PER_ORIGIN": "2",
                "HOORI_OUTGOING_CALLS": "2" if role == "shopping" else "4", "HOORI_OUTGOING_PENDING_CALLS": "0",
                "HOORI_GATEWAY_PERMISSIONS": "recipes:read,pantry:read,shopping:read"}
            main = "hoori/micro/" + role.capitalize() if role in ("registry", "gateway") else "dev/hoori/micro/demo/CompositionMain"
            role_cp = cp
            if role == "facade":
                main, role_cp = "dev/hoori/micro/facade/FacadeChecks", facade_cp
                env.update({"PATH": "/nonexistent", "JAVA_HOME": "/nonexistent"})
            log = (ROOT / f".cache/composition-{engine}-{role}.log").open("w")
            logs[role] = log
            processes[role] = subprocess.Popen([str(runtime / "bin/hoori"), "run", "--engine", engine, "--live-output",
                "--graceful-signals", "--max-heap-bytes", "33554432", "--allow-environment-read", "--allow-network-listen", "--allow-resource-read",
                "--allow-network-connect", "--class-path", ":".join(map(str, role_cp)), main], env=env, stdout=log, stderr=log)
            until(lambda: request(role, "/health/ready")[0] == 200, role + " ready")

        expected = {"recipe": {"id": 1, "title": "Kartoffelsuppe"}, "available": ["Kartoffeln", "Möhren"]}
        until(lambda: request("gateway", "/overview/1")[0] == 200, "discovered overview")
        assert result("shopping", "/local") == expected
        assert result("gateway", "/overview/1", headers={"X-Request-ID": "two-providers", "Authorization": "SECRET"}) == expected
        assert all(probe(role)["requestId"] == "two-providers" and not probe(role)["credentials"] for role in ("recipes", "pantry"))
        evidence["checks"].append("ordinary route and Gateway use two discovered typed providers with correct correlation")

        def invalid(response, expected):
            status, raw = response
            assert status == 400 and json.loads(raw) == {"code": "validation_failed", "violations": expected}, (status, raw)

        positive = [{"path": "id", "code": "positive"}]
        invalid(request("gateway", "/recipes/0"), positive)
        invalid(request("gateway", "/validation", {"id": 0}), positive)
        invalid(request("shopping", "/_hoori/invoke", {"id": 0},
                        {"X-Hoori-Action": "shopping.validated", "X-Hoori-Version": "1"}), positive)
        assert result("shopping", "/validation-calls") == 0, "Invalid input reached the business handler"
        assert result("gateway", "/validation", {"id": "1"}) == {"id": 1}
        assert result("shopping", "/validation-calls") == 1
        before = probe("recipes")["calls"]
        invalid(request("gateway", "/meals/bulk", {"ids": [1, 0, None]}),
                [{"path": "ids[1]", "code": "positive"}, {"path": "ids[2]", "code": "not_null"}])
        assert probe("recipes")["calls"] == before, "Invalid batch started downstream work"
        for body in ({}, {"id": "not-a-number"}, {"id": None}):
            assert request("gateway", "/validation", body)[0] == 400
        status, raw = request("shopping", "/_hoori/invoke", {"id": 1},
                              {"X-Hoori-Action": "shopping.broken-validator", "X-Hoori-Version": "1"})
        assert status == 500 and b"PRIVATE" not in raw, (status, raw)
        assert result("shopping", "/validation-calls") == 1
        status, raw = request("gateway", "/downstream-invalid", {})
        assert status == 502 and b"validation_failed" not in raw and b"positive" not in raw, (status, raw)
        assert result("gateway", "/validation", {"id": 1}) == {"id": 1}
        evidence["checks"].append("generated input codecs and explicit validators: direct RPC/Gateway field errors, no invalid handler/fanout, safe internal/nested failures and recovery")

        with ThreadPoolExecutor(max_workers=4) as pool:
            if args.facade:
                assert "facade_lazy=true" in Path(logs["facade"].name).read_text()
                until(lambda: request("facade", "/overview", {"id": 1})[0] == 200, "generated discovered clients")
                assert result("facade", "/overview", {"id": 1}) == expected
                for identity in ("facade-first", "facade-reused"):
                    assert result("facade", "/reused", headers={"X-Request-ID": identity}) == expected
                    assert all(probe(role)["requestId"] == identity for role in ("recipes", "pantry"))
                assert request("facade", "/checked")[0] == 200
                assert request("facade", "/overview", {"id": 0})[0] == 400
                assert request("facade", "/overview", {"id": 999})[0] == 502
                for path, maximum in (("/overview", 800), ("/short", 250)):
                    for role in ("recipes", "pantry"):
                        gate(role, "closed")
                    work = pool.submit(request, "facade", path, {"id": 1})
                    until(lambda: all(probe(role)["active"] == 1 for role in ("recipes", "pantry")), "facade starts both calls")
                    assert all(0 < probe(role)["budgetMillis"] <= maximum for role in ("recipes", "pantry"))
                    assert work.result()[0] == 504, "Scoped/parent deadline was extended"
                    for role in ("recipes", "pantry"):
                        gate(role, "open")
                    until(lambda: all(probe(role)["active"] == 0 for role in ("recipes", "pantry")), "facade remote recovery")
                    assert result("facade", "/overview", {"id": 1}) == expected
                evidence["checks"].append("generated facade lazy/reused with current context; checked errors, 800ms method/250ms parent bounds, cancellation/recovery; no build tools on runtime path")

            for role in ("recipes", "pantry"):
                gate(role, "closed")
            call = pool.submit(request, "gateway", "/overview/1")
            until(lambda: all(probe(role)["active"] == 1 for role in ("recipes", "pantry")), "both providers before release")
            assert not call.done()
            for role in ("recipes", "pantry"):
                gate(role, "open")
            assert call.result()[0] == 200
            evidence["checks"].append("two real provider handlers entered before either response was released")

            for role in ("recipes", "pantry"):
                gate(role, "closed")
            call = pool.submit(request, "gateway", "/overview/1")
            until(lambda: all(probe(role)["active"] == 1 for role in ("recipes", "pantry")), "fail-fast sibling wait")
            gate("recipes", "fail")
            status, raw = call.result()
            assert status == 502 and b"Controlled provider" not in raw
            assert probe("pantry")["active"] == 1, "No distributed remote-cancel guarantee is assumed"
            for role in ("recipes", "pantry"):
                gate(role, "open")
            until(lambda: probe("pantry")["active"] == 0, "remote provider finishes independently")
            gate("pantry", "fail")
            dashboard = result("gateway", "/dashboard/1")
            assert dashboard["recipe"]["status"] == "ok" and dashboard["pantry"] == {"status": "unavailable"}
            gate("pantry", "open")
            assert result("gateway", "/dashboard/2")["pantry"] == {"status": "ok", "data": []}
            evidence["checks"].append("fail-fast returns after local cancellation; optional unavailable differs from successful empty")

            ids = [2, 1, 2, 1, 2]
            gate("recipes", "closed")
            batch = pool.submit(request, "gateway", "/meals/batch", {"ids": ids})
            until(lambda: probe("recipes")["active"] == 2, "bounded batch reaches two calls")
            assert not batch.done()
            gate("recipes", "open")
            status, raw = batch.result()
            assert status == 200 and [r["id"] for r in json.loads(raw)] == ids
            before = probe("recipes")["calls"]
            assert [r["id"] for r in result("gateway", "/meals/bulk", {"ids": ids})] == ids
            assert probe("recipes")["calls"] == before + 1
            assert probe("recipes")["maximum"] == 2
            before = probe("recipes")["calls"]
            assert request("gateway", "/meals/batch", {"ids": [1, 999, 2, 1, 2]})[0] == 502
            assert probe("recipes")["calls"] == before + 5, "All-complete batch stopped early on a local failure"
            for bad in ({"ids": [1] * 17}, {"ids": [0]}, {}):
                assert request("gateway", "/meals/bulk", bad)[0] == 400, bad
            evidence["checks"].append("batch bound/order/all-complete; bulk performs one RPC; malformed/oversized input rejected")

            gate("recipes", "closed")
            held = [pool.submit(request, "gateway", "/meals/1") for _ in range(2)]
            until(lambda: probe("recipes")["active"] == 2, "global outgoing admission saturated")
            status, _ = request("shopping", "/_hoori/invoke", {"id": 1},
                                {"X-Hoori-Action": "shopping.dashboard", "X-Hoori-Version": "1"})
            assert status == 503, "Settled swallowed global admission failure"
            gate("recipes", "open")
            assert all(work.result()[0] == 200 for work in held)
            assert result("gateway", "/overview/1") == expected
            evidence["checks"].append("settled does not turn global admission rejection into an optional result; recovery succeeds")

            for role in ("recipes", "pantry"):
                gate(role, "closed")
            call = pool.submit(request, "gateway", "/overview/1")
            until(lambda: all(probe(role)["active"] == 1 for role in ("recipes", "pantry")), "fanout before SIGTERM")
            stop("shopping")
            assert call.result()[0] in (502, 503)
            for role in ("recipes", "pantry"):
                gate(role, "open")
            evidence["checks"].append("SIGTERM drains local fanout against actual Hoori providers")
        for role in (["facade"] if args.facade else []) + ["gateway", "recipes", "pantry", "registry"]:
            stop(role)
        target = ROOT / f".cache/composition-{'facade-' if args.facade else ''}{engine}.json"
        target.write_text(json.dumps(evidence, indent=2) + "\n")
        print(f"PASS: real discovered composition, batch/bulk and shutdown; {target}")
    finally:
        for proc in processes.values():
            if proc.poll() is None:
                proc.kill()
            proc.wait(timeout=5)
        for log in logs.values():
            log.close()


if __name__ == "__main__":
    main()
