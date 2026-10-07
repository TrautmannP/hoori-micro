#!/usr/bin/env python3
"""Generated MVC bindings, literal data and publication diagnostics on the pinned guest."""
from http.client import HTTPConnection
from pathlib import Path
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
    runtime = ROOT / ".docker-context/runtime"
    receipt = verify(runtime)
    engine = os.environ.get("HOORI_ENGINE", "mixed")
    jars = [jar("framework", "hoori-micro"), jar("examples/mvc-crud", "mvc-crud")]
    cp = [*jars, ROOT / "examples/mvc-crud/target/test-classes", *runtime_classpath(runtime, receipt)]
    classes = {"registry": "hoori/micro/Registry", "probe": "probe/ProbeApplication", "gateway": "hoori/micro/Gateway"}
    ports, processes, logs = {}, {}, {}
    evidence = {"engine": engine, "runtime": receipt["source"], "checks": [],
                "jars": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in jars}}
    for role in classes:
        with socket.socket() as available:
            available.bind(("127.0.0.1", 0))
            ports[role] = available.getsockname()[1]

    def request(role, method, path, payload=None):
        connection = HTTPConnection("127.0.0.1", ports[role], timeout=10)
        try:
            connection.request(method, path, None if payload is None else json.dumps(payload).encode(),
                               {"Content-Type": "application/json"})
            response = connection.getresponse()
            return response.status, response.read()
        finally:
            connection.close()

    def value(role, path):
        status, body = request(role, "GET", path)
        assert status == 200, (role, path, status, body)
        return json.loads(body)

    def metrics(role):
        status, body = request(role, "GET", "/metrics")
        assert status == 200
        return {key: int(number) for key, number in (line.rsplit(" ", 1) for line in body.decode().splitlines()
                if line.startswith("hoori_micro_") and not line.startswith("#"))}

    def until(predicate):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            for role, process in processes.items():
                assert process.poll() is None, Path(logs[role].name).read_text()
            try:
                if predicate():
                    return
            except (OSError, ConnectionError):
                pass
            time.sleep(.05)
        raise AssertionError("OpenAPI fixture did not converge")

    def launch(role):
        env = os.environ | {"PATH": "/nonexistent", "JAVA_HOME": "/nonexistent",
            "HOORI_BIND_ADDRESS": "127.0.0.1", "HOORI_PORT": str(ports[role]), "HOORI_INSTANCE_ID": role,
            "HOORI_REGISTRY_URL": f"http://127.0.0.1:{ports['registry']}",
            "HOORI_ADVERTISE_URL": f"http://127.0.0.1:{ports[role]}",
            "HOORI_HEARTBEAT_MS": "100", "HOORI_REGISTRY_TTL_MS": "3000", "HOORI_CATALOG_MAX_AGE_MS": "1500",
            "HOORI_CONTROL_TIMEOUT_MS": "1000", "HOORI_CLIENT_TIMEOUT_MS": "5000", "HOORI_WORK_TIMEOUT_MS": "6000",
            "HOORI_REQUEST_TIMEOUT_MS": "10000", "HOORI_SHUTDOWN_GRACE_MS": "1500",
            "HOORI_OPENAPI_ENABLED": "true", "HOORI_GATEWAY_PERMISSIONS": "probe:read"}
        if role not in logs:
            logs[role] = (ROOT / f".cache/openapi-{engine}-{role}.log").open("w")
        processes[role] = subprocess.Popen([str(runtime / "bin/hoori"), "run", "--engine", engine,
            "--live-output", "--graceful-signals", "--max-heap-bytes", "33554432",
            "--allow-environment-read", "--allow-network-listen", "--allow-network-connect", "--allow-resource-read",
            "--class-path", ":".join(map(str, cp)), classes[role]], env=env, stdout=logs[role], stderr=logs[role])
        until(lambda: request(role, "GET", "/health/ready")[0] == 200)

    def stop(role):
        process = processes.pop(role)
        process.send_signal(signal.SIGTERM)
        assert process.wait(timeout=15) == 0, Path(logs[role].name).read_text()

    try:
        for role in classes:
            launch(role)
        until(lambda: request("gateway", "GET", "/openapi.json")[0] == 200
              and len(value("gateway", "/openapi.json")["paths"]) == 3)
        until(lambda: metrics("gateway")["hoori_micro_catalog_complete"] == 1
              and metrics("gateway")["hoori_micro_openapi_available"] == 1)
        for role in ("probe", "gateway"):
            assert value(role, "/probe/openapi/defaults") == "20:"
            assert value(role, "/probe/openapi/defaults?limit=7&text=chosen") == "7:chosen"
            assert value(role, "/probe/openapi/literal") == {"$ref": "literal application data"}
            for byte, short in ((-128, -32768), (127, 32767)):
                assert value(role, f"/probe/openapi/range?byte={byte}&short={short}") == byte + short
            for byte, short in ((-129, 0), (128, 0), (0, -32769), (0, 32768)):
                assert request(role, "GET", f"/probe/openapi/range?byte={byte}&short={short}")[0] == 400
        document = value("gateway", "/openapi.json")
        defaults = document["paths"]["/probe/openapi/defaults"]["get"]["parameters"]
        assert len(defaults) == 2 and {p["name"]: p["schema"]["default"] for p in defaults} == {"limit": 20, "text": ""}
        literal = document["paths"]["/probe/openapi/literal"]["get"]["responses"]["200"]["content"]["application/json"]
        assert literal["schema"]["properties"]["$ref"] == {"type": "string"}
        assert literal["example"] == {"$ref": "#/components/schemas/Literal"}
        assert literal["examples"]["sample"]["value"] == {"$ref": "literal application data"}
        manifest = value("gateway", "/_hoori/publication")
        assert manifest["publicationId"] == document["x-hoori-publication-id"]
        for row in manifest["operations"]:
            status, raw = request("probe", "GET", "/_hoori/openapi/" + row["contractHash"])
            assert status == 200 and hashlib.sha256(raw).hexdigest() == row["contractHash"]
        evidence["checks"].append("generated direct/gateway defaults, native integer boundaries, effective overrides and literal $ref publication")

        healthy = metrics("gateway")
        assert healthy["hoori_micro_registry_available"] == healthy["hoori_micro_catalog_fresh"] == healthy["hoori_micro_openapi_available"] == 1
        assert metrics("probe")["hoori_micro_registration_active"] == 1
        assert metrics("probe")['hoori_micro_openapi_state{reason="disabled"}'] == 1
        missing = {"id": "missing", "service": "missing", "version": 1,
            "url": f"http://127.0.0.1:{ports['probe']}", "contractHash": "0" * 64, "apiGroup": "public",
            "endpoints": [{"method": "GET", "path": "/missing", "consumes": "", "produces": "application/json",
                           "permission": "missing:read", "operationHash": "0" * 64}]}
        assert request("registry", "PUT", "/_hoori/instances/missing", missing)[0] == 200
        until(lambda: metrics("gateway").get('hoori_micro_openapi_state{reason="artifact"}') == 1)
        failures = metrics("gateway")["hoori_micro_openapi_refresh_failures_total"]
        assert failures > 0 and request("gateway", "GET", "/openapi.json")[0] == 503
        assert value("gateway", "/probe/openapi/literal") == {"$ref": "literal application data"}
        assert request("gateway", "GET", "/health/ready")[0] == 200
        logged_bytes = Path(logs["gateway"].name).stat().st_size
        until(lambda: metrics("gateway")["hoori_micro_openapi_refresh_failures_total"] > failures)
        assert Path(logs["gateway"].name).stat().st_size == logged_bytes
        assert request("registry", "DELETE", "/_hoori/instances/missing")[0] == 204
        until(lambda: metrics("gateway")["hoori_micro_openapi_available"] == 1)
        assert metrics("gateway")['hoori_micro_openapi_state{reason="none"}'] == 1
        evidence["checks"].append("enabled/disabled state, repeated missing-artifact failures and complete recovery without changing business readiness")

        registration = next(i for i in value("registry", "/_hoori/catalog")["instances"] if i["service"] == "probe")
        conflict = json.loads(json.dumps(registration))
        conflict["id"] = "conflict"
        conflict["endpoints"] = [e for e in conflict["endpoints"] if e["path"] == "/probe/openapi/literal"]
        conflict["endpoints"][0]["permission"] = "probe:conflict"
        assert request("registry", "PUT", "/_hoori/instances/conflict", conflict)[0] == 200
        until(lambda: metrics("gateway")["hoori_micro_gateway_routes_withheld"] == 1)
        assert request("gateway", "GET", "/probe/openapi/literal")[0] == 404
        assert request("registry", "DELETE", "/_hoori/instances/conflict")[0] == 204
        until(lambda: metrics("gateway")["hoori_micro_gateway_routes_withheld"] == 0)
        stop("registry")
        until(lambda: metrics("gateway")["hoori_micro_registry_available"] == 0
              and metrics("gateway")["hoori_micro_catalog_fresh"] == 0)
        assert request("gateway", "GET", "/openapi.json")[0] == 503
        assert metrics("gateway")['hoori_micro_openapi_state{reason="catalog"}'] == 1
        until(lambda: metrics("probe")["hoori_micro_registration_active"] == 0)
        launch("registry")
        until(lambda: metrics("gateway")["hoori_micro_openapi_available"] == 1)
        until(lambda: metrics("probe")["hoori_micro_registration_active"] == 1)
        until(lambda: metrics("gateway")["hoori_micro_catalog_complete"] == 1
              and metrics("gateway")["hoori_micro_openapi_available"] == 1)
        evidence["checks"].append("withheld conflict, registry outage, catalog/registration expiry and restart recovery")
        evidence["metrics"] = metrics("gateway")
        for role in reversed(list(processes)):
            stop(role)
        evidence["complete"] = True
    finally:
        for process in processes.values():
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)
        for log in logs.values():
            log.close()
        (ROOT / f".cache/openapi-{engine}.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print(json.dumps(evidence, indent=2))


if __name__ == "__main__":
    main()
