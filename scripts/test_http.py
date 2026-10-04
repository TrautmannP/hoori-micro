#!/usr/bin/env python3
"""Real generated applications: HTTP contracts through discovery, clients and gateway."""
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
    modules = [("framework", "hoori-micro"), ("examples/demo-contracts", "hoori-micro-demo-contracts")]
    modules += [(f"examples/{name}-service", f"{name}-service") for name in ("recipes", "pantry", "shopping")]
    jars = [jar(*module) for module in modules]
    cp = [*jars, *runtime_classpath(runtime, receipt)]
    roles = {"registry": "hoori/micro/Registry", "recipes": "dev/hoori/micro/recipes/RecipesApplication",
             "pantry": "dev/hoori/micro/pantry/PantryApplication", "shopping": "dev/hoori/micro/shopping/ShoppingApplication",
             "gateway": "hoori/micro/Gateway"}
    ports, processes, logs = {}, {}, {}
    evidence = {"engine": engine, "runtime": receipt["source"], "checks": [],
                "jars": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in jars}}
    for role in roles:
        with socket.socket() as available:
            available.bind(("127.0.0.1", 0))
            ports[role] = available.getsockname()[1]

    def request(role, method, path, body=None, headers=None):
        connection = HTTPConnection("127.0.0.1", ports[role], timeout=15)
        try:
            data = json.dumps(body).encode() if isinstance(body, (dict, list)) else body
            connection.request(method, path, data, {"Content-Type": "application/json", **(headers or {})})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def until(check):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            for role, process in processes.items():
                assert process.poll() is None, Path(logs[role].name).read_text()
            try:
                if check():
                    return
            except (ConnectionError, OSError):
                pass
            time.sleep(.025)
        raise AssertionError("HTTP discovery did not become ready")

    def value(role, path):
        status, _, body = request(role, "GET", path)
        assert status == 200, (role, path, status, body)
        return json.loads(body)

    try:
        for role, main_class in roles.items():
            env = os.environ | {"PATH": "/nonexistent", "JAVA_HOME": "/nonexistent",
                "HOORI_BIND_ADDRESS": "127.0.0.1", "HOORI_PORT": str(ports[role]), "HOORI_INSTANCE_ID": role,
                "HOORI_REGISTRY_URL": f"http://127.0.0.1:{ports['registry']}",
                "HOORI_ADVERTISE_URL": f"http://127.0.0.1:{ports[role]}",
                "HOORI_HEARTBEAT_MS": "500", "HOORI_REGISTRY_TTL_MS": "6000", "HOORI_CATALOG_MAX_AGE_MS": "15000",
                "HOORI_CONTROL_TIMEOUT_MS": "3000", "HOORI_CLIENT_TIMEOUT_MS": "10000", "HOORI_WORK_TIMEOUT_MS": "12000",
                "HOORI_REQUEST_TIMEOUT_MS": "15000", "HOORI_SHUTDOWN_GRACE_MS": "1500",
                "HOORI_OPENAPI_ENABLED": "true",
                "HOORI_GATEWAY_PERMISSIONS": "recipes:read,recipes:write,pantry:read,shopping:read,shopping:write,shopping:demo"}
            log = (ROOT / f".cache/http-{engine}-{role}.log").open("w")
            logs[role] = log
            processes[role] = subprocess.Popen([str(runtime / "bin/hoori"), "run", "--engine", engine,
                "--live-output", "--graceful-signals", "--max-heap-bytes", "33554432",
                "--allow-environment-read", "--allow-network-listen", "--allow-network-connect", "--allow-resource-read",
                "--class-path", ":".join(map(str, cp)), main_class], env=env, stdout=log, stderr=log)
            until(lambda: request(role, "GET", "/health/ready")[0] == 200)

        until(lambda: request("gateway", "GET", "/meals/1")[0] == 200)
        publication = []
        def same_publication():
            status, headers, raw = request("gateway", "GET", "/openapi.json")
            manifest_status, _, manifest_raw = request("gateway", "GET", "/_hoori/publication")
            if status != 200 or manifest_status != 200:
                return False
            manifest = json.loads(manifest_raw)
            if headers["X-Hoori-Publication"] != manifest["publicationId"] or len(manifest["operations"]) != 15:
                return False
            publication[:] = [manifest, json.loads(raw), raw]
            return True
        until(same_publication)
        manifest, public_api, raw = publication
        offered = {(row["method"].lower(), row["path"]) for row in manifest["operations"]}
        documented = {(method, path) for path, methods in public_api["paths"].items() for method in methods}
        assert offered == documented and len(documented) == 15, (offered, documented)
        assert manifest["complete"] and not manifest["undocumented"] and not manifest["withheld"]
        assert "/recipes/bulk" not in public_api["paths"] and b"$ref" not in raw
        assert request("gateway", "GET", "/_hoori/docs")[0] == 200
        assert request("gateway", "GET", "/_hoori/openapi/groups/missing")[0] == 404
        for row in manifest["operations"]:
            status, headers, body = request(row["service"], "GET", "/_hoori/openapi/" + row["contractHash"])
            assert status == 200 and hashlib.sha256(body).hexdigest() == row["contractHash"]
            assert headers["ETag"] == '"' + row["contractHash"] + '"'
            assert request(row["service"], "GET", "/_hoori/openapi/" + "0" * 64)[0] == 404
        evidence["checks"].append("OpenAPI canonical artifact hashes, exact gateway publication, local references, hidden internal endpoints and local docs")
        print("PASS: OpenAPI publication and service artifacts", flush=True)
        expected = {"id": 1, "title": "Kartoffelsuppe"}
        assert value("recipes", "/recipes/1") == value("shopping", "/meals/1") == value("gateway", "/meals/1") == expected
        for role, path in (("recipes", "/recipes"), ("shopping", "/meals"), ("gateway", "/recipes"), ("gateway", "/meals")):
            status, headers, body = request(role, "POST", path, {"title": "Möhren + 100% Äpfel"})
            assert status == 201 and headers["Location"].startswith("/recipes/"), (role, status, headers, body)
            created = json.loads(body)
            assert created["title"] == "Möhren + 100% Äpfel"
            selected = value(role, path + "?prefix=M%C3%B6hren%20%2B%20100%25")
            assert selected == [created], (role, selected)
            assert request(role, "GET", path + "?prefix=a&prefix=b")[0] == 400
            status, _, body = request(role, "DELETE", path + "/" + str(created["id"]))
            assert status == 204 and body == b"", (role, status, body)
            assert request(role, "GET", path + "/" + str(created["id"]))[0] == 404
        evidence["checks"].append("direct/client/gateway CRUD: exact path/query UTF-8, 201/relative Location, typed result, void/204, public 404")
        print("PASS: direct/client/gateway CRUD", flush=True)

        for role, path in (("recipes", "/recipes"), ("shopping", "/meals"), ("gateway", "/recipes"), ("gateway", "/meals")):
            for method, target, body in (("GET", path + "/0", None), ("POST", path, {"title": " "})):
                status, _, raw = request(role, method, target, body)
                assert status == 400 and json.loads(raw)["code"] == "validation_failed", (role, status, raw)
            assert request(role, "POST", path, b'{"title":7}')[0] == 400
            assert request(role, "POST", path, {"title": "ok"}, {"Content-Type": "text/plain"})[0] == 415
            assert request(role, "GET", path, headers={"Accept": "text/plain"})[0] == 406
        assert request("gateway", "POST", "/recipes/bulk", {"ids": [1]})[0] == 405
        status, headers, _ = request("gateway", "PATCH", "/recipes/1")
        assert status == 405 and "GET" in headers["Allow"] and "DELETE" in headers["Allow"], (status, headers)
        assert request("recipes", "GET", "/recipes/1", headers={"X-Hoori-Version": "999", "X-Hoori-Endpoint": "wrong"})[0] == 421
        assert request("gateway", "GET", "/meals/1", headers={"X-Hoori-Version": "999", "X-Hoori-Endpoint": "wrong"})[0] == 200
        assert request("gateway", "POST", "/_hoori/invoke", {})[0] == 404
        evidence["checks"].append("bounded validation and media errors, unpublished route, 405/Allow, endpoint/version mismatch, external internal headers ignored")
        for data in ({"ids": None}, {"ids": [1, 0, None]}, {"ids": [1] * 17}):
            assert request("gateway", "POST", "/meals/bulk", data)[0] == 400
        for raw in (b'{}', b'{"ids":[] ,"ids":[]}', b'{"ids":[]} false'):
            assert request("gateway", "POST", "/meals/bulk", raw)[0] == 400
        print("PASS: validation, media and publication boundaries", flush=True)

        for identity in ("first-request", "reused-client"):
            status, _, raw = request("gateway", "GET", "/demo/context", headers={"X-Request-ID": identity,
                "Authorization": "PRIVATE", "Cookie": "PRIVATE"})
            assert status == 200, (status, raw)
            context = json.loads(raw)
            assert context["requestId"] == identity and not context["authorization"], context
        overview = value("gateway", "/overview/1")
        assert overview == {"recipe": expected, "available": ["Kartoffeln", "Möhren"]}, overview
        dashboard = value("gateway", "/dashboard/2")
        assert dashboard["pantry"] == {"status": "ok", "data": []}, dashboard
        for path in ("batch", "bulk"):
            status, _, raw = request("gateway", "POST", "/meals/" + path, {"ids": [2, 1, 2]})
            assert status == 200 and [r["id"] for r in json.loads(raw)] == [2, 1, 2], (status, raw)
        evidence["checks"].append("singleton clients use current request identity, no credentials, overview/dashboard and ordered batch/bulk")

        for role in reversed(list(roles)):
            process = processes[role]
            process.send_signal(signal.SIGTERM)
            assert process.wait(timeout=15) == 0, Path(logs[role].name).read_text()
            if role != "registry":
                assert "roots_drained=true drained=true" in Path(logs[role].name).read_text()
        evidence["checks"].append("all generated applications and gateway drain cleanly on SIGTERM")
        evidence["complete"] = True
    finally:
        for process in processes.values():
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)
        for log in logs.values():
            log.close()
        (ROOT / f".cache/http-{engine}.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print(json.dumps(evidence, indent=2))


if __name__ == "__main__":
    main()
