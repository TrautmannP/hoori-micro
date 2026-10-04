#!/usr/bin/env python3
"""Real Docker/Hoori acceptance gate. Fails rather than falling back to a host JVM."""
from __future__ import annotations
from concurrent.futures import ThreadPoolExecutor
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

from benchmark import kernel_sample, percentile
from runtime_check import verify
from build_rolling import build as build_rolling

ROOT = Path(__file__).resolve().parents[1]
ENV = os.environ.copy()
ENV.setdefault("HOORI_DEMO_PORT", "18080")
PROJECT = "hoori-micro-check-" + str(os.getpid())
BASE = "http://127.0.0.1:" + ENV["HOORI_DEMO_PORT"]
COMPOSE = ["docker", "compose", "--project-name", PROJECT, "--file", str(ROOT / "compose.yaml")]
RESULT = {"schema": 1, "project": PROJECT, "engine": ENV.get("HOORI_ENGINE", "mixed"), "snapshots": []}


def compose(*args: str, capture: bool = False) -> str:
    result = subprocess.run(COMPOSE + list(args), cwd=ROOT, env=ENV, check=True,
                            text=True, stdout=subprocess.PIPE if capture else None, timeout=900)
    return result.stdout.strip() if capture else ""


def request(path: str, request_id: str = "smoke", timeout: float = 65, payload=None):
    req = urllib.request.Request(BASE + path, data=None if payload is None else json.dumps(payload).encode(),
                                 headers={"X-Request-ID": request_id, "Content-Type": "application/json",
                                                      "Authorization": "Bearer MUST-NOT-BE-FORWARDED"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read()


def require(condition: bool, message: str):
    if not condition:
        raise AssertionError(message)


def eventually(path: str, expected, what: str) -> None:
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            status, _, body = request(path)
            if status == 200 and json.loads(body) == expected:
                return
        except (OSError, ValueError):
            pass
        # These are NEW independent, read-only requests, not an SDK retry policy.
        time.sleep(0.5)
    raise AssertionError(what)


def ready_meal() -> None:
    eventually("/meals/1", {"id": 1, "title": "Kartoffelsuppe"}, "HTTP call did not recover")


def openapi_publication(recommendation=False, recipes=True) -> None:
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        status, headers, raw = request("/openapi.json")
        manifest_status, _, manifest_raw = request("/_hoori/publication")
        if status == manifest_status == 200:
            document, manifest = json.loads(raw), json.loads(manifest_raw)
            fields = {name.lower(): value for name, value in headers.items()}
            documented = {(method.upper(), path) for path, methods in document["paths"].items() for method in methods}
            offered = {(row["method"], row["path"]) for row in manifest["operations"]}
            expected = 11 + (4 if recipes else 0) + (1 if recommendation else 0)
            if (fields.get("x-hoori-publication") == manifest["publicationId"] == document["x-hoori-publication-id"]
                    and manifest["complete"] and documented == offered and len(documented) == expected
                    and (("GET", "/recipes/{id}/recommendation") in documented) == recommendation):
                require(not manifest["undocumented"] and not manifest["withheld"], "unexpected OpenAPI omissions")
                require("/recipes/bulk" not in document["paths"] and b'"$ref"' not in raw, "internal route or unresolved reference")
                RESULT.setdefault("openapi", []).append({"publicationId": manifest["publicationId"], "operations": len(documented)})
                return
        time.sleep(.2)
    raise AssertionError("OpenAPI never matched the gateway publication")


def unavailable_contract() -> None:
    # A real provider serves no artifact at this advertised hash. The original HTTP client must reject it.
    status, _, body = registry_request("GET", "/v2/catalog")
    origin = next(i["url"] for i in json.loads(body)["instances"] if i["service"] == "recipes")
    probe = {"id": "openapi-probe", "service": "docprobe", "version": 1, "url": origin,
             "contractHash": "0" * 64, "apiGroup": "public", "endpoints": [{"method": "GET", "path": "/docprobe",
             "consumes": "", "produces": "application/json", "permission": "docprobe:read", "operationHash": "0" * 64}]}
    require(registry_request("PUT", "/v2/instances/openapi-probe", payload=probe)[0] == 200, "contract probe registration")
    try:
        deadline = time.monotonic() + 15
        while True:
            status, _, raw = request("/_hoori/publication")
            if status == 200 and any(row["path"] == "/docprobe" for row in json.loads(raw)["operations"]):
                break
            require(time.monotonic() < deadline, "contract probe was not published")
            time.sleep(.1)
        require(request("/openapi.json")[0] == 503, "missing artifact was published or old docs mislabeled as current")
        require(request("/meals/1")[0] == 200, "contract fetch blocked business calls")
        require(request("/health/ready")[0] == 200, "contract failure changed gateway readiness")
    finally:
        registry_request("DELETE", "/v2/instances/openapi-probe")
    openapi_publication()
    print("PASS: missing OpenAPI artifact fails publication while business calls and readiness continue")


def registered(expected=None) -> dict:
    """Services with their HTTP endpoints, once the registry reports a complete catalog."""
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            catalog = json.loads(compose("exec", "-T", "registry", "curl", "-fsS", "--max-time", "2",
                                         "-H", "X-Hoori-Catalog-Protocol: 4", "http://127.0.0.1:8080/v2/catalog", capture=True))
            if catalog["complete"]:
                services = {i["service"]: sorted(a["method"] + " " + a["path"] for a in i["endpoints"]) for i in catalog["instances"]}
                if expected is None or services == expected:
                    return services
        except (subprocess.SubprocessError, ValueError, KeyError):
            pass
        time.sleep(0.5)
    raise AssertionError("Registry catalog never became complete")


def container(service: str) -> str:
    return compose("ps", "-q", service, capture=True)


def registry_request(method: str, path: str, headers=None, payload=None):
    args = ["exec", "-T", "registry", "curl", "-sS", "--max-time", "10", "--dump-header", "-",
            "--write-out", "\nEND", "-X", method]
    for name, value in ({"X-Hoori-Catalog-Protocol": "4"} | (headers or {})).items():
        args += ["-H", name + ": " + value]
    if payload is not None:
        args += ["-H", "Content-Type: application/json", "--data-binary", json.dumps(payload)]
    text = compose(*args, "http://127.0.0.1:8080" + path, capture=True)
    head, body = text.removesuffix("\nEND").split("\n\n", 1)
    fields = dict(line.split(":", 1) for line in head.splitlines()[1:])
    return int(head.split()[1]), {name.lower(): value.strip() for name, value in fields.items()}, body


def discovery_protocol() -> None:
    path = "/v2/instances/protocol-probe"
    small = {"id": "protocol-probe", "service": "probe", "version": 1,
             "url": "http://recipes:8080", "endpoints": [{"method": "GET", "path": "/ping", "consumes": "", "produces": "application/json"}]}
    status, _, body = registry_request("PUT", path, {"X-Hoori-Catalog-Protocol": "3"}, small)
    require(status == 426, "previous protocol must be rejected")
    status, _, body = registry_request("PUT", path, {"X-Hoori-Catalog-Protocol": "4"}, small)
    require(status == 200, "current endpoint protocol registration")
    catalog = json.loads(body)
    known = {"X-Hoori-Catalog-Protocol": "4", "X-Hoori-Catalog-Epoch": catalog["epoch"],
             "X-Hoori-Catalog-Revision": str(catalog["revision"])}
    for _ in range(3):
        status, fields, body = registry_request("POST", path + "/lease", known)
        require(status == 204 and body == "", "unchanged lease must have no catalog body")
        require(fields["x-hoori-catalog-epoch"] == catalog["epoch"]
                and fields["x-hoori-catalog-revision"] == str(catalog["revision"]), "lease changed the revision")
    require(registry_request("GET", "/v2/catalog", known)[0] == 204, "conditional catalog")
    filtered = dict(known, **{"X-Hoori-Catalog-View": "services=recipes:1"})
    status, fields, body = registry_request("GET", "/v2/catalog", filtered)
    consumer = json.loads(body)
    require(status == 200 and fields["x-hoori-catalog-view"] == "services=recipes:1",
            "a different view must receive its full snapshot")
    require(len(consumer["instances"]) == 1 and consumer["instances"][0]["service"] == "recipes"
            and all(set(a) == {"method", "path", "consumes", "produces"} for a in consumer["instances"][0]["endpoints"]),
            "consumer view includes unrelated services or publication metadata")
    filtered["X-Hoori-Catalog-Known-View"] = "services=recipes:1"
    require(registry_request("GET", "/v2/catalog", filtered)[0] == 204, "filtered confirmation")
    for view in ("none", "public"):
        filtered["X-Hoori-Catalog-View"] = view
        status, _, body = registry_request("GET", "/v2/catalog", filtered)
        require(status == 200, "changed filter received the previous view's confirmation")
        instances = json.loads(body)["instances"]
        require(not instances if view == "none" else
                bool(instances) and all("path" in a and "permission" in a for i in instances for a in i["endpoints"]),
                "provider/public view contains foreign/private endpoints")
    require(registry_request("POST", path + "/lease",
                             dict(known, **{"X-Hoori-Catalog-View": "services=recipes:0"}))[0] == 400,
            "invalid filter must fail before lease renewal")
    require(registry_request("PUT", path, {"X-Hoori-Catalog-Protocol": "1"}, small)[0] == 426,
            "unsupported protocol must fail before changing state")
    # Each replacement fits the HTTP document limit; their aggregate cannot fit the catalog.
    big = dict(small)
    big["endpoints"] = [{"method": "GET", "consumes": "", "produces": "application/json", "path": "/probe/" + str(i) + "/" + "x" * 220,
                       "permission": "probe:read"} for i in range(128)]
    require(len(json.dumps(big).encode()) < 65536, "metadata fixture exceeds individual body bound")
    status, _, _ = registry_request("PUT", path, known, big)
    # This fits once, but another provider with equally sized metadata would overflow the full view.
    require(status == 200, "first bounded large registration")
    other = dict(big, id="overflow-probe")
    require(registry_request("PUT", "/v2/instances/overflow-probe", known, other)[0] == 413,
            "aggregate overflow must be rejected")
    status, _, body = registry_request("GET", "/v2/catalog")
    require(status == 200 and all(i["id"] != "overflow-probe" for i in json.loads(body)["instances"]),
            "rejected metadata was committed")
    require(registry_request("DELETE", path, known)[0] == 204, "deregistration")
    require(registry_request("POST", path + "/lease", known)[0] == 404, "deleted lease must require registration")
    print("PASS: native leases, view-bound filtering, protocol transition and aggregate byte limit")


def runtime_stats(service: str) -> dict:
    text = compose("exec", "-T", service, "curl", "-fsS", "--max-time", "2",
                   "http://127.0.0.1:8080/metrics", capture=True)
    return {line.split()[0]: int(line.split()[1]) for line in text.splitlines()
            if line.startswith(("hoori_micro_pool_", "hoori_micro_calls_"))}


def identity(service: str) -> dict:
    item = json.loads(subprocess.check_output(["docker", "inspect", container(service)], text=True))[0]
    return {"container": item["Id"], "image": item["Image"], "started": item["State"]["StartedAt"],
            "pid": item["State"]["Pid"], "memory_limit": item["HostConfig"]["Memory"],
            "cpu_limit": item["HostConfig"]["NanoCpus"], "user": item["Config"]["User"],
            "read_only": item["HostConfig"]["ReadonlyRootfs"], "cap_drop": item["HostConfig"]["CapDrop"]}


def snapshot(phase: str) -> None:
    values = {}
    for role in ("registry", "recipes", "pantry", "shopping", "gateway"):
        current = identity(role)
        text = compose("exec", "-T", role, "curl", "-fsS", "--max-time", "2",
                       "http://127.0.0.1:8080/metrics", capture=True)
        metrics = {line.split()[0]: int(line.split()[1]) for line in text.splitlines()
                   if line.startswith("hoori_micro_")}
        kernel = kernel_sample(current["pid"])
        require(metrics["hoori_micro_heap_used_bytes"] <= 33554432, "guest heap exceeded test budget")
        require(kernel["memory_current"] <= current["memory_limit"], "container exceeded test memory budget")
        for direction in ("incoming", "outgoing"):
            require(metrics[f'hoori_micro_calls_active{{direction="{direction}"}}'] == 0
                    and metrics[f'hoori_micro_calls_pending{{direction="{direction}"}}'] == 0,
                    "idle snapshot retained admission work")
        require(metrics['hoori_micro_pool_pending_acquires{pool="data"}'] == 0
                and metrics['hoori_micro_pool_pending_acquires{pool="control"}'] == 0,
                "idle snapshot retained SDK waiters")
        values[role] = {"identity": current, "metrics": metrics, "kernel": kernel}
    RESULT["snapshots"].append({"phase": phase, "roles": values})


def health_cost() -> None:
    current = identity("gateway")
    before = kernel_sample(current["pid"])
    started = time.monotonic()
    compose("exec", "-T", "gateway", "sh", "-c",
            'i=0; while [ "$i" -lt 50 ]; do curl -fsS --max-time 2 http://127.0.0.1:8080/health/ready >/dev/null || exit; i=$((i+1)); done')
    after = kernel_sample(current["pid"])
    RESULT["healthcheck"] = {"probes": 50, "elapsed_seconds": time.monotonic() - started,
                            "cgroup_cpu_usec": after["cpu"]["usage_usec"] - before["cpu"]["usage_usec"]}


def saturated_pool() -> None:
    # Three calls hold one outgoing permit and two waiters before encoding for > registry TTL.
    started = time.monotonic()
    require(all(value == 0 for value in runtime_stats("registry").values()), "Registry has active business/control work")
    with ThreadPoolExecutor(max_workers=3) as executor:
        calls = [executor.submit(request, "/demo/slow/1", "pool-saturated-" + str(i)) for i in range(3)]
        deadline, saturated = time.monotonic() + 30, False
        while not all(call.done() for call in calls):
            stats = runtime_stats("shopping")
            active = stats['hoori_micro_calls_active{direction="outgoing"}']
            pending = stats['hoori_micro_calls_pending{direction="outgoing"}']
            saturated |= active == 1 and pending == 2
            require(active <= 1 and pending <= 2, "outgoing admission exceeded bounds")
            require(stats['hoori_micro_pool_pending_acquires{pool="data"}'] == 0,
                    "a second SDK queue formed behind application admission")
            require(stats['hoori_micro_pool_pending_acquires{pool="control"}'] == 0
                    and stats['hoori_micro_pool_active_connections{pool="control"}'] <= 1,
                    "control pool exceeded bounds")
            _, _, body = registry_request("GET", "/v2/catalog")
            require(any(i["service"] == "shopping" for i in json.loads(body)["instances"]),
                    "saturated data pool prevented lease renewal")
            require(time.monotonic() < deadline, "saturation calls exceeded native test deadline")
            time.sleep(.25)
        require(saturated and all(call.result()[0] == 200 for call in calls), "bounded saturation fixture")
        require(time.monotonic() - started > 6, "saturation did not cover one registry TTL")
    print("PASS: data saturation preserves discovery, one control connection and zero control waiters")


def main() -> int:
    if not (ROOT / ".docker-context/runtime/DISTRIBUTION.json").exists():
        print("Run scripts/build.sh with a verified Hoori distribution first", file=sys.stderr)
        return 2
    RESULT["runtime"] = verify(ROOT / ".docker-context/runtime")
    RESULT["script_sha256"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    RESULT["acceptance"] = {"cycles": 3, "per_role_cpus": .5, "per_role_memory_bytes": 268435456,
                            "guest_heap_bytes": 33554432, "idle_seconds": 5.5}
    old_replica = None
    registry_paused = False
    traffic_stop, traffic_phase, traffic_results = threading.Event(), ["rolling"], []
    traffic_thread = None
    work = tempfile.TemporaryDirectory(prefix="hoori-micro-smoke-")
    override = Path(work.name) / "pool.json"
    settings = {role: {"cpus": .5, "mem_limit": "256m", "environment": {"HOORI_CATALOG_MAX_AGE_MS": "10000"}}
                for role in ("registry", "recipes", "pantry", "shopping", "gateway")}
    settings["shopping"]["environment"].update({
        "HOORI_CLIENT_CONNECTIONS": "1", "HOORI_CLIENT_PER_ORIGIN": "1", "HOORI_CLIENT_PENDING_ACQUIRES": "0",
        "HOORI_OUTGOING_CALLS": "1", "HOORI_OUTGOING_PENDING_CALLS": "2"})
    override.write_text(json.dumps({"services": settings}))
    COMPOSE.extend(["--file", str(override)])
    try:
        build_rolling(ROOT / ".docker-context/runtime")
        compose("up", "--build", "--detach", "--wait", "--wait-timeout", "180")
        ready_meal()
        eventually("/overview/1", {"recipe": {"id": 1, "title": "Kartoffelsuppe"},
                   "available": ["Kartoffeln", "Möhren"]}, "typed two-provider overview")
        eventually("/dashboard/2", {"recipe": {"status": "ok", "data": {"id": 2, "title": "Apfelstrudel"}},
                   "pantry": {"status": "ok", "data": []}}, "successful empty dashboard section")
        for path in ("/meals/batch", "/meals/bulk"):
            status, _, body = request(path, payload={"ids": [2, 1, 2, 1, 2]})
            require(status == 200 and [item["id"] for item in json.loads(body)] == [2, 1, 2, 1, 2],
                    "bounded batch/bulk ordering")
        eventually("/recipes/1", {"id": 1, "title": "Kartoffelsuppe"}, "published recipes endpoint")
        require(request("/health/live")[0] == 200, "liveness")
        require(request("/health/ready")[0] == 200, "readiness")
        require(request("/meals/999")[0] == 404, "domain 404 across two hops")
        require(request("/meals/not-a-number")[0] == 400, "input validation")
        require(request("/recipes/1/recommendation")[0] == 404, "unpublished endpoint must not exist yet")
        require(request("/_hoori/invoke")[0] == 404, "internal invoke endpoint is not public")
        status, headers, body = request("/demo/context", "hop-check-1")
        require(status == 200 and json.loads(body) == {"requestId": "hop-check-1", "authorization": False},
                "request ID across gateway→shopping→recipes without credential forwarding")
        normalized = {key.lower(): value for key, value in headers.items()}
        require(normalized.get("x-request-id") == "hop-check-1", "response correlation")
        status, _, body = request("/metrics")
        require(status == 200 and b"hoori_http" in body, "HTTP metrics")
        expected = {
            "recipes": ["DELETE /recipes/{id}", "GET /recipes", "GET /recipes/demo/context", "GET /recipes/demo/slow/{id}", "GET /recipes/{id}", "POST /recipes", "POST /recipes/bulk"],
            "pantry": ["GET /pantry/{id}"],
            "shopping": ["DELETE /meals/{id}", "GET /dashboard/{id}", "GET /demo/context", "GET /demo/slow/{id}", "GET /meals", "GET /meals/{id}", "GET /overview/{id}", "POST /meals", "POST /meals/batch", "POST /meals/bulk"]}
        require(registered() == expected, "catalog lists exactly the HTTP contracts")
        openapi_publication()
        require(request("/_hoori/docs")[0] == 200, "local API reference")
        print("PASS: gateway publication, endpoint calls, domain errors, health, metrics and context")

        discovery_protocol()
        unavailable_contract()
        # Warm compilation before making the cooperative waiting/saturation assertion.
        require(request("/demo/slow/1", "saturation-warmup")[0] == 200, "warm saturation route")
        health_cost()
        time.sleep(5.5)
        snapshot("warm_idle")
        for cycle in range(3):
            saturated_pool()
            time.sleep(5.5)
            snapshot("burst_idle_" + str(cycle))

        stable = {name: identity(name) for name in ("gateway", "shopping")}
        def traffic():
            while not traffic_stop.is_set():
                started = time.monotonic()
                phase, status = traffic_phase[0], 0
                try:
                    status, _, body = request("/meals/1", "continuous", timeout=5)
                    if status == 200 and json.loads(body) != {"id": 1, "title": "Kartoffelsuppe"}:
                        status = -1
                except (OSError, ValueError):
                    pass
                traffic_results.append((phase, status, 1000 * (time.monotonic() - started)))
                if len(traffic_results) >= 20000:
                    traffic_stop.set()
                traffic_stop.wait(.05)
        traffic_thread = threading.Thread(target=traffic)
        traffic_thread.start()
        # Old/new providers share the previous 0.5 CPU budget during rolling correctness checks.
        settings["recipes"]["cpus"] = .25
        override.write_text(json.dumps({"services": settings}))
        old_replica = compose("run", "--detach", "--no-deps", "--name", PROJECT + "-recipes-old",
                              "recipes", capture=True).splitlines()[-1]
        settings["recipes"]["build"] = {"args": {"SERVICE": "recipes-next"}}
        override.write_text(json.dumps({"services": settings}))
        compose("up", "--build", "--detach", "--force-recreate", "--wait", "--wait-timeout", "180", "recipes")
        eventually("/recipes/1/recommendation", {"id": 2, "title": "Apfelstrudel"}, "new endpoint not published")
        deadline = time.monotonic() + 60
        while True:
            _, _, body = registry_request("GET", "/v2/catalog")
            providers = [i for i in json.loads(body)["instances"] if i["service"] == "recipes"]
            if len(providers) == 2:
                break
            require(time.monotonic() < deadline, "old/new replicas did not register simultaneously")
            time.sleep(.5)
        require(sorted(len(i["endpoints"]) for i in providers) == [7, 8], "rolling endpoint fixture")
        for provider in providers:
            # HOSTNAME advertises the individual container, never the shared recipes DNS alias.
            status = compose("exec", "-T", "shopping", "curl", "-sS", "--max-time", "10", "-o", "/dev/null",
                             "-w", "%{http_code}", "-X", "GET", "-H", "X-Hoori-Endpoint: GET /recipes/{id}/recommendation - application/json",
                             "-H", "X-Hoori-Version: 1", provider["url"] + "/recipes/1/recommendation", capture=True)
            require(status == ("200" if any(a["path"] == "/recipes/{id}/recommendation" for a in provider["endpoints"]) else "404"),
                    "advertise URL did not reach the selected instance")
        for _ in range(8):
            status, _, body = request("/recipes/1/recommendation")
            require(status == 200 and json.loads(body) == {"id": 2, "title": "Apfelstrudel"},
                    "new endpoint was routed to an old provider")
        openapi_publication(recommendation=True)
        subprocess.run(["docker", "stop", "--timeout", "15", old_replica], check=True, timeout=30)
        subprocess.run(["docker", "rm", old_replica], check=True, timeout=30)
        old_replica = None
        # Wait for the consumer to drop the stopped replica, rather than accepting one lucky RR pick.
        deadline, consecutive = time.monotonic() + 60, 0
        while consecutive < 4:
            status, _, body = request("/meals/1")
            consecutive = consecutive + 1 if status == 200 and json.loads(body) == {
                "id": 1, "title": "Kartoffelsuppe"} else 0
            require(time.monotonic() < deadline, "consumer retained the stopped old replica")
            time.sleep(.1)
        ready_meal()
        require({name: identity(name) for name in stable} == stable, "gateway/shopping were redeployed")
        print("PASS: old/new replicas select by endpoint and exact advertise address; gateway/shopping unchanged")

        traffic_phase[0] = "registry_timeout"
        compose("pause", "registry")
        registry_paused = True
        time.sleep(4)
        compose("unpause", "registry")
        registry_paused = False
        ready_meal()
        deadline = time.monotonic() + 30
        while "type=java.net.SocketTimeoutException" not in compose("logs", "--no-color", "shopping", capture=True):
            require(time.monotonic() < deadline, "delayed registry did not exercise a real control timeout")
            time.sleep(.2)
        traffic_phase[0] = "registry_outage"
        compose("stop", "registry")
        require(request("/meals/1")[0] == 200, "calls must not depend on a reachable registry")
        deadline = time.monotonic() + 20
        while request("/meals/1")[0] != 404:
            require(time.monotonic() < deadline, "gateway kept an expired routing snapshot")
            time.sleep(.2)
        require(request("/health/ready")[0] == 200, "snapshot expiry changed local readiness")
        require(request("/openapi.json")[0] == 503 and request("/_hoori/publication")[0] == 503,
                "expired routing retained a current documentation publication")
        compose("up", "--detach", "--wait", "--wait-timeout", "180", "registry")
        expected["recipes"] = sorted(expected["recipes"] + ["GET /recipes/{id}/recommendation"])
        registered(expected)
        ready_meal()
        print("PASS: control timeout under business load, bounded snapshot expiry and epoch recovery")
        openapi_publication(recommendation=True)

        traffic_phase[0] = "provider_crash"
        old_provider = identity("recipes")
        _, _, body = registry_request("GET", "/v2/catalog")
        old_ids = {i["id"] for i in json.loads(body)["instances"] if i["service"] == "recipes"}
        compose("kill", "--signal", "SIGKILL", "recipes")
        status, _, body = request("/meals/1")
        require(status in (502, 503, 504), "bounded upstream failure")
        require(b"Exception" not in body and b"_hoori" not in body, "safe public error")
        require(request("/health/live")[0] == 200, "upstream failure must not break local liveness")
        require(request("/health/ready")[0] == 200, "no recursive readiness dependency by default")
        deadline = time.monotonic() + 15
        while True:
            _, _, body = registry_request("GET", "/v2/catalog")
            if not old_ids.intersection(i["id"] for i in json.loads(body)["instances"]):
                break
            require(time.monotonic() < deadline, "crashed provider did not expire by TTL")
            time.sleep(.2)
        openapi_publication(recipes=False)
        settings["recipes"]["cpus"] = .5
        override.write_text(json.dumps({"services": settings}))
        compose("up", "--build", "--detach", "--force-recreate", "--wait", "--wait-timeout", "180", "recipes")
        ready_meal()
        require(identity("recipes")["container"] != old_provider["container"], "provider was not replaced")
        traffic_phase[0] = "catalog_cycles"
        for enabled in ("0", "1"):
            settings["recipes"]["build"] = {"args": {"SERVICE": "recipes-next" if enabled == "1" else "recipes"}}
            override.write_text(json.dumps({"services": settings}))
            compose("up", "--build", "--detach", "--force-recreate", "--wait", "--wait-timeout", "180", "recipes")
            if enabled == "1":
                eventually("/recipes/1/recommendation", {"id": 2, "title": "Apfelstrudel"}, "route did not reappear")
            else:
                deadline = time.monotonic() + 30
                while request("/recipes/1/recommendation")[0] != 404:
                    require(time.monotonic() < deadline, "removed route stayed published")
                    time.sleep(.2)
            ready_meal()
            openapi_publication(recommendation=enabled == "1")
        require({name: identity(name) for name in stable} == stable, "recovery restarted consumer/gateway")
        traffic_stop.set()
        traffic_thread.join(timeout=10)
        require(not traffic_thread.is_alive() and len(traffic_results) < 20000, "load generator did not stop within bounds")
        require(all(status in (0, 200, 404, 502, 503, 504) for _, status, _ in traffic_results), "wrong business output under load")
        RESULT["traffic"] = {phase: {"statuses": dict(Counter(str(status) for name, status, _ in traffic_results if name == phase)),
                                     "p99_ms": percentile([ms for name, _, ms in traffic_results if name == phase], .99)}
                             for phase in dict.fromkeys(name for name, _, _ in traffic_results)}
        require(sum(status == 200 for _, status, _ in traffic_results) > 0, "no successful continuous work")
        time.sleep(5.5)
        snapshot("rolling_recovery_idle")
        print("PASS: continuous calls during rolling, TTL/crash replacement and repeated route removal/republication")

        require(request("/demo/slow/1", "slow-warmup")[0] == 200, "warm slow route")
        with ThreadPoolExecutor(max_workers=2) as executor:
            results = [executor.submit(request, "/demo/slow/1", "drain-in-flight"),
                       executor.submit(request, "/demo/slow/1", "drain-pending")]
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                logs = compose("logs", "--no-color", "recipes", capture=True)
                if any("demo_slow_started id=" + name in logs for name in ("drain-in-flight", "drain-pending")) and runtime_stats("shopping")[
                        'hoori_micro_calls_pending{direction="outgoing"}'] == 1:
                    break
                time.sleep(0.1)
            else:
                raise AssertionError("Upstream never observed the in-flight drain request")
            require(all(not result.done() for result in results), "Drain fixture already completed before shutdown")
            compose("pause", "registry")
            registry_paused = True
            compose("stop", "shopping")
            compose("unpause", "registry")
            registry_paused = False
            require(sorted(result.result(timeout=20)[0] for result in results) == [200, 503],
                    "stop must drain admitted data and reject work still waiting before encoding")
        logs = compose("logs", "--no-color", "shopping", capture=True)
        require("drained=true" in logs, "graceful shutdown was not reported")
        cid = compose("ps", "--all", "-q", "shopping", capture=True)
        code = subprocess.check_output(["docker", "inspect", "--format", "{{.State.ExitCode}}", cid], text=True).strip()
        require(code == "0", "non-zero runtime exit: " + code)
        RESULT["complete"] = True
        print("PASS: SIGTERM with unavailable control drains admitted data, rejects waiting work, then closes pools")
        return 0
    except (OSError, ValueError, AssertionError, subprocess.SubprocessError) as error:
        print(f"SMOKE FAILED: {error}", file=sys.stderr)
        try:
            compose("logs", "--no-color", "--tail", "120")
        except (OSError, subprocess.SubprocessError):
            pass
        return 1
    finally:
        # Only this uniquely named test project; never the user's normal demo/production stack.
        traffic_stop.set()
        if traffic_thread is not None:
            traffic_thread.join(timeout=10)
        if registry_paused:
            try:
                compose("unpause", "registry")
            except subprocess.SubprocessError:
                pass
        if old_replica:
            subprocess.run(["docker", "rm", "--force", old_replica], check=False, timeout=30)
        try:
            compose("down", "--remove-orphans")
        except (OSError, subprocess.SubprocessError) as error:
            print(f"Test project cleanup failed ({PROJECT}): {error}", file=sys.stderr)
        work.cleanup()
        output = ROOT / ".cache" / (PROJECT + ".json")
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(RESULT, indent=2) + "\n")
        print("Smoke evidence:", output)


if __name__ == "__main__":
    raise SystemExit(main())
