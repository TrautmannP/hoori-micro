#!/usr/bin/env python3
"""Real Docker/Hoori acceptance gate. Fails rather than falling back to a host JVM."""
from __future__ import annotations
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ENV = os.environ.copy()
ENV.setdefault("HOORI_DEMO_PORT", "18080")
PROJECT = "hoori-micro-check-" + str(os.getpid())
BASE = "http://127.0.0.1:" + ENV["HOORI_DEMO_PORT"]
COMPOSE = ["docker", "compose", "--project-name", PROJECT, "--file", str(ROOT / "compose.yaml")]


def compose(*args: str, capture: bool = False) -> str:
    result = subprocess.run(COMPOSE + list(args), cwd=ROOT, env=ENV, check=True,
                            text=True, stdout=subprocess.PIPE if capture else None, timeout=900)
    return result.stdout.strip() if capture else ""


def request(path: str, request_id: str = "smoke", timeout: float = 65):
    req = urllib.request.Request(BASE + path, headers={"X-Request-ID": request_id,
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
    eventually("/meals/1", {"id": 1, "title": "Kartoffelsuppe"}, "Action call did not recover")


def registered(expected=None) -> dict:
    """Services with their action names, once the registry reports a complete catalog."""
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            catalog = json.loads(compose("exec", "-T", "registry", "curl", "-fsS", "--max-time", "2",
                                         "http://127.0.0.1:8080/v1/catalog", capture=True))
            if catalog["complete"]:
                services = {i["service"]: sorted(a["name"] for a in i["actions"]) for i in catalog["instances"]}
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
    for name, value in (headers or {}).items():
        args += ["-H", name + ": " + value]
    if payload is not None:
        args += ["-H", "Content-Type: application/json", "--data-binary", json.dumps(payload)]
    text = compose(*args, "http://127.0.0.1:8080" + path, capture=True)
    head, body = text.removesuffix("\nEND").split("\n\n", 1)
    fields = dict(line.split(":", 1) for line in head.splitlines()[1:])
    return int(head.split()[1]), {name.lower(): value.strip() for name, value in fields.items()}, body


def discovery_protocol() -> None:
    path = "/v1/instances/legacy-probe"
    small = {"id": "legacy-probe", "service": "probe", "version": 1,
             "url": "http://recipes:8080", "actions": [{"name": "ping"}]}
    status, _, body = registry_request("PUT", path, payload=small)
    require(status == 200, "legacy registration compatibility")
    catalog = json.loads(body)
    known = {"X-Hoori-Catalog-Protocol": "2", "X-Hoori-Catalog-Epoch": catalog["epoch"],
             "X-Hoori-Catalog-Revision": str(catalog["revision"])}
    for _ in range(3):
        status, fields, body = registry_request("POST", path + "/lease", known)
        require(status == 204 and body == "", "unchanged lease must have no catalog body")
        require(fields["x-hoori-catalog-epoch"] == catalog["epoch"]
                and fields["x-hoori-catalog-revision"] == str(catalog["revision"]), "lease changed the revision")
    require(registry_request("GET", "/v1/catalog", known)[0] == 204, "conditional catalog")
    filtered = dict(known, **{"X-Hoori-Catalog-View": "services=recipes:1"})
    status, fields, body = registry_request("GET", "/v1/catalog", filtered)
    consumer = json.loads(body)
    require(status == 200 and fields["x-hoori-catalog-view"] == "services=recipes:1",
            "a different view must receive its full snapshot")
    require(len(consumer["instances"]) == 1 and consumer["instances"][0]["service"] == "recipes"
            and all(set(a) == {"name"} for a in consumer["instances"][0]["actions"]),
            "consumer view includes unrelated services or publication metadata")
    filtered["X-Hoori-Catalog-Known-View"] = "services=recipes:1"
    require(registry_request("GET", "/v1/catalog", filtered)[0] == 204, "filtered confirmation")
    for view in ("none", "public"):
        filtered["X-Hoori-Catalog-View"] = view
        status, _, body = registry_request("GET", "/v1/catalog", filtered)
        require(status == 200, "changed filter received the previous view's confirmation")
        instances = json.loads(body)["instances"]
        require(not instances if view == "none" else
                bool(instances) and all("path" in a and "permission" in a for i in instances for a in i["actions"]),
                "provider/public view contains foreign/private actions")
    require(registry_request("POST", path + "/lease",
                             dict(known, **{"X-Hoori-Catalog-View": "services=recipes:0"}))[0] == 400,
            "invalid filter must fail before lease renewal")
    require(registry_request("PUT", path, {"X-Hoori-Catalog-Protocol": "1"}, small)[0] == 426,
            "unsupported protocol must fail before changing state")
    # Each replacement fits the HTTP document limit; their aggregate cannot fit the catalog.
    big = dict(small)
    big["actions"] = [{"name": f"action-{i}", "method": "GET", "path": "/probe/" + str(i) + "/" + "x" * 220,
                       "permission": "probe:read"} for i in range(128)]
    require(len(json.dumps(big).encode()) < 65536, "metadata fixture exceeds individual body bound")
    status, _, _ = registry_request("PUT", path, known, big)
    # This fits once, but another provider with equally sized metadata would overflow the full view.
    require(status == 200, "first bounded large registration")
    other = dict(big, id="overflow-probe")
    require(registry_request("PUT", "/v1/instances/overflow-probe", known, other)[0] == 413,
            "aggregate overflow must be rejected")
    status, _, body = registry_request("GET", "/v1/catalog")
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
            _, _, body = registry_request("GET", "/v1/catalog")
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
    old_replica = None
    work = tempfile.TemporaryDirectory(prefix="hoori-micro-smoke-")
    override = Path(work.name) / "pool.json"
    override.write_text(json.dumps({"services": {"shopping": {"environment": {
        "HOORI_CLIENT_CONNECTIONS": "1", "HOORI_CLIENT_PER_ORIGIN": "1", "HOORI_CLIENT_PENDING_ACQUIRES": "0",
        "HOORI_OUTGOING_CALLS": "1", "HOORI_OUTGOING_PENDING_CALLS": "2"}}}}))
    COMPOSE.extend(["--file", str(override)])
    try:
        ENV["HOORI_DEMO_RECOMMEND"] = "0"
        compose("up", "--build", "--detach", "--wait", "--wait-timeout", "180")
        ready_meal()
        eventually("/recipes/1", {"id": 1, "title": "Kartoffelsuppe"}, "published recipes action")
        require(request("/health/live")[0] == 200, "liveness")
        require(request("/health/ready")[0] == 200, "readiness")
        require(request("/meals/999")[0] == 404, "domain 404 across two hops")
        require(request("/meals/not-a-number")[0] == 400, "input validation")
        require(request("/recipes/1/recommendation")[0] == 404, "unpublished action must not exist yet")
        require(request("/_hoori/invoke")[0] == 404, "internal invoke endpoint is not public")
        status, headers, body = request("/demo/context", "hop-check-1")
        require(status == 200 and json.loads(body) == {"requestId": "hop-check-1", "authorization": False},
                "request ID across gateway→shopping→recipes without credential forwarding")
        normalized = {key.lower(): value for key, value in headers.items()}
        require(normalized.get("x-request-id") == "hop-check-1", "response correlation")
        status, _, body = request("/metrics")
        require(status == 200 and b"hoori_http" in body, "HTTP metrics")
        require(registered() == {"recipes": ["context", "get", "slow"], "shopping": ["context", "meal", "slow"]},
                "catalog lists exactly the defined actions")
        print("PASS: gateway publication, action calls, domain errors, health, metrics and context")

        discovery_protocol()
        # Warm compilation before making the cooperative waiting/saturation assertion.
        require(request("/demo/slow/1", "saturation-warmup")[0] == 200, "warm saturation route")
        saturated_pool()

        stable = {name: container(name) for name in ("gateway", "shopping")}
        old_replica = compose("run", "--detach", "--no-deps", "--name", PROJECT + "-recipes-old",
                              "-e", "HOORI_DEMO_RECOMMEND=0", "recipes", capture=True).splitlines()[-1]
        ENV["HOORI_DEMO_RECOMMEND"] = "1"
        compose("up", "--detach", "--force-recreate", "--wait", "--wait-timeout", "180", "recipes")
        eventually("/recipes/1/recommendation", {"id": 2, "title": "Apfelstrudel"}, "new action not published")
        deadline = time.monotonic() + 60
        while True:
            _, _, body = registry_request("GET", "/v1/catalog")
            providers = [i for i in json.loads(body)["instances"] if i["service"] == "recipes"]
            if len(providers) == 2:
                break
            require(time.monotonic() < deadline, "old/new replicas did not register simultaneously")
            time.sleep(.5)
        require(sorted(len(i["actions"]) for i in providers) == [3, 4], "rolling action fixture")
        for provider in providers:
            # HOSTNAME advertises the individual container, never the shared recipes DNS alias.
            status = compose("exec", "-T", "shopping", "curl", "-sS", "--max-time", "10", "-o", "/dev/null",
                             "-w", "%{http_code}", "-X", "POST", "-H", "X-Hoori-Action: recipes.recommend",
                             "-H", "X-Hoori-Version: 1", "-H", "Content-Type: application/json", "--data", '{"id":1}',
                             provider["url"] + "/_hoori/invoke", capture=True)
            require(status == ("200" if len(provider["actions"]) == 4 else "421"),
                    "advertise URL did not reach the selected instance")
        for _ in range(8):
            status, _, body = request("/recipes/1/recommendation")
            require(status == 200 and json.loads(body) == {"id": 2, "title": "Apfelstrudel"},
                    "new action was routed to an old provider")
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
        require({name: container(name) for name in stable} == stable, "gateway/shopping were redeployed")
        print("PASS: old/new replicas select by action and exact advertise address; gateway/shopping unchanged")

        compose("stop", "registry")
        require(request("/meals/1")[0] == 200, "calls must not depend on a reachable registry")
        compose("up", "--detach", "--wait", "--wait-timeout", "180", "registry")
        registered({"recipes": ["context", "get", "recommend", "slow"],
                    "shopping": ["context", "meal", "slow"]})
        ready_meal()
        print("PASS: registry outage and restart with re-registration")

        compose("stop", "recipes")
        status, _, body = request("/meals/1")
        require(status in (502, 503), "bounded upstream failure")
        require(b"Exception" not in body and b"_hoori" not in body, "safe public error")
        require(request("/health/live")[0] == 200, "upstream failure must not break local liveness")
        require(request("/health/ready")[0] == 200, "no recursive readiness dependency by default")
        compose("up", "--detach", "--wait", "--wait-timeout", "180", "recipes")
        ready_meal()
        print("PASS: dependency outage and recovery")

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
            compose("stop", "shopping")
            require(sorted(result.result(timeout=20)[0] for result in results) == [200, 503],
                    "stop must drain admitted data and reject work still waiting before encoding")
        logs = compose("logs", "--no-color", "shopping", capture=True)
        require("drained=true" in logs, "graceful shutdown was not reported")
        cid = compose("ps", "--all", "-q", "shopping", capture=True)
        code = subprocess.check_output(["docker", "inspect", "--format", "{{.State.ExitCode}}", cid], text=True).strip()
        require(code == "0", "non-zero runtime exit: " + code)
        print("PASS: SIGTERM drains admitted data, rejects waiting work before encoding, then closes pools")
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
        if old_replica:
            subprocess.run(["docker", "rm", "--force", old_replica], check=False, timeout=30)
        try:
            compose("down", "--remove-orphans")
        except (OSError, subprocess.SubprocessError) as error:
            print(f"Test project cleanup failed ({PROJECT}): {error}", file=sys.stderr)
        work.cleanup()


if __name__ == "__main__":
    raise SystemExit(main())
