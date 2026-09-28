#!/usr/bin/env python3
"""Real Docker/Hoori acceptance gate. Fails rather than falling back to a host JVM."""
from __future__ import annotations
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import subprocess
import sys
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


def ready_meal() -> None:
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            status, _, body = request("/demo/meal/1")
            if status == 200 and json.loads(body) == {"id": 1, "title": "Kartoffelsuppe"}:
                return
        except (OSError, ValueError):
            pass
        # These are NEW independent, read-only requests, not an SDK retry policy.
        time.sleep(0.5)
    raise AssertionError("Named recipe call did not recover")


def main() -> int:
    if not (ROOT / ".docker-context/runtime/DISTRIBUTION.json").exists():
        print("Run scripts/build.sh with a verified Hoori distribution first", file=sys.stderr)
        return 2
    try:
        compose("up", "--build", "--detach", "--wait", "--wait-timeout", "180")
        ready_meal()
        require(request("/health/live")[0] == 200, "liveness")
        require(request("/health/ready")[0] == 200, "readiness")
        require(request("/demo/meal/999")[0] == 404, "domain 404 mapping")
        require(request("/demo/meal/not-a-number")[0] == 400, "input validation")
        status, headers, body = request("/demo/context", "hop-check-1")
        require(status == 200 and body == b"hop-check-1", "upstream context / credential isolation")
        normalized = {key.lower(): value for key, value in headers.items()}
        require(normalized.get("x-request-id") == "hop-check-1", "response correlation")
        status, _, body = request("/metrics")
        require(status == 200 and b"hoori_http" in body, "HTTP metrics")
        print("PASS: routing, typed JSON, domain errors, health, metrics and context")

        compose("stop", "recipes")
        status, _, body = request("/demo/meal/1")
        require(status in (502, 503), "bounded upstream failure")
        require(b"Exception" not in body and b"recipes:8080" not in body, "safe public error")
        require(request("/health/live")[0] == 200, "upstream failure must not break local liveness")
        require(request("/health/ready")[0] == 200, "no recursive readiness dependency by default")
        compose("up", "--detach", "--wait", "--wait-timeout", "180", "recipes")
        ready_meal()
        previous = compose("ps", "-q", "recipes", capture=True)
        compose("up", "--detach", "--force-recreate", "--wait", "--wait-timeout", "180", "recipes")
        require(compose("ps", "-q", "recipes", capture=True) != previous, "container was not recreated")
        ready_meal()
        # Re-creation may reuse its IP. This gate does NOT claim proof of DNS TTL/rotation behavior.
        print("PASS: dependency outage, recovery and container re-creation")

        require(request("/demo/slow", "slow-warmup")[0] == 200, "warm slow route")
        with ThreadPoolExecutor(max_workers=1) as executor:
            result = executor.submit(request, "/demo/slow", "drain-in-flight")
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                logs = compose("logs", "--no-color", "recipes", capture=True)
                if "demo_slow_started id=drain-in-flight" in logs:
                    break
                time.sleep(0.1)
            else:
                raise AssertionError("Upstream never observed the in-flight drain request")
            require(not result.done(), "Drain fixture already completed before shutdown; rerun")
            compose("stop", "shopping")
            require(result.result(timeout=20)[0] == 200, "in-flight call was aborted instead of drained")
        logs = compose("logs", "--no-color", "shopping", capture=True)
        require("drained=true" in logs, "graceful shutdown was not reported")
        cid = compose("ps", "--all", "-q", "shopping", capture=True)
        code = subprocess.check_output(["docker", "inspect", "--format", "{{.State.ExitCode}}", cid], text=True).strip()
        require(code == "0", "non-zero runtime exit: " + code)
        print("PASS: SIGTERM drains an active outbound call before pool cleanup")
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
        try:
            compose("down", "--remove-orphans")
        except (OSError, subprocess.SubprocessError) as error:
            print(f"Test project cleanup failed ({PROJECT}): {error}", file=sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main())
