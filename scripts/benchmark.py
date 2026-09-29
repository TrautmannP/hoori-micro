#!/usr/bin/env python3
"""Bounded external load against real, pinned Docker/Hoori processes. No retries."""
from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import hashlib
from http.client import HTTPConnection, HTTPException
import json
import math
import os
from pathlib import Path
import random
import resource
import subprocess
import tempfile
import threading
import time

from runtime_check import ROOT, verify

ROLES = ("registry", "recipes", "shopping", "gateway")


def percentile(values: list[float], fraction: float) -> float | None:
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)] if values else None


def summarize(results: list[tuple[int, float, float]], offered: int, elapsed: float,
              p99_limit: float, error_limit: float) -> dict:
    successful = [latency for status, latency, _ in results if status == 200]
    latencies = [latency for _, latency, _ in results]
    failed = len(results) - len(successful)
    not_started = offered - len(results)
    p99 = percentile(latencies, .99)
    error_rate = (failed + not_started) / offered if offered else 1.0
    return {"offered": offered, "started": len(results), "successful": len(successful),
            "failed": failed, "not_started": not_started,
            "statuses": dict(Counter(str(status) for status, _, _ in results)),
            "elapsed_seconds": elapsed, "successful_per_second": len(successful) / elapsed,
            "successful_per_minute": 60 * len(successful) / elapsed,
            "p50_ms": percentile(latencies, .50), "p95_ms": percentile(latencies, .95),
            "p99_ms": p99, "successful_p99_ms": percentile(successful, .99),
            "generator_start_lag_p99_ms": percentile([lag for _, _, lag in results], .99),
            "error_fraction": error_rate,
            "accepted": p99 is not None and p99 <= p99_limit and error_rate <= error_limit}


def load(port: int, path: str, payload: dict, seconds: float, workers: int, rate: float,
         p99_limit: float, error_limit: float) -> dict:
    body = json.dumps(payload, separators=(",", ":")).encode()
    local = threading.local()
    results = []
    connections = []
    lock = threading.Lock()
    slots = threading.BoundedSemaphore(workers)
    started = time.monotonic()
    generator_before = resource.getrusage(resource.RUSAGE_SELF)
    deadline = started + seconds

    def send(planned: float):
        actual = time.monotonic()
        status = 0
        try:
            if not hasattr(local, "connection"):
                local.connection = HTTPConnection("127.0.0.1", port, timeout=10)
                with lock:
                    connections.append(local.connection)
            connection = local.connection
            connection.request("POST", path, body, {"Content-Type": "application/json",
                                                    "Accept": "application/json", "X-Request-ID": "benchmark"})
            response = connection.getresponse()
            answer = response.read()
            status = response.status
            if status == 200 and json.loads(answer) != payload:
                status = -1  # HTTP success with incorrect business output is a failure.
        except (OSError, ValueError, HTTPException):
            status = 0
            if hasattr(local, "connection"):
                local.connection.close()
                del local.connection
        finally:
            with lock:
                results.append((status, 1000 * (time.monotonic() - planned), 1000 * (actual - planned)))
            slots.release()

    def closed_worker():
        try:
            while time.monotonic() < deadline:
                slots.acquire()
                send(time.monotonic())
        finally:
            if hasattr(local, "connection"):
                local.connection.close()

    offered = 0
    futures = []
    with ThreadPoolExecutor(max_workers=workers) as executor:
        if rate == 0:
            for _ in range(workers):
                futures.append(executor.submit(closed_worker))
        else:
            # ponytail: no generator queue; an occupied worker rejects the offered arrival.
            offered = math.ceil(seconds * rate)
            for i in range(offered):
                planned = started + i / rate
                time.sleep(max(0, planned - time.monotonic()))
                if slots.acquire(blocking=False):
                    futures.append(executor.submit(send, planned))
            time.sleep(max(0, deadline - time.monotonic()))
    for connection in connections:
        connection.close()
    for future in futures:
        future.result()
    elapsed = time.monotonic() - started
    outcome = summarize(results, len(results) if rate == 0 else offered, elapsed, p99_limit, error_limit)
    generator_after = resource.getrusage(resource.RUSAGE_SELF)
    outcome["generator_cpu_seconds"] = (generator_after.ru_utime + generator_after.ru_stime
                                         - generator_before.ru_utime - generator_before.ru_stime)
    return outcome


def http(port: int, path: str, method: str = "GET", payload=None):
    connection = HTTPConnection("127.0.0.1", port, timeout=10)
    try:
        body = None if payload is None else json.dumps(payload).encode()
        connection.request(method, path, body, {"Content-Type": "application/json"})
        response = connection.getresponse()
        data = response.read()
        if response.status >= 400:
            raise RuntimeError(f"Probe status {response.status}: {path}")
        return data
    finally:
        connection.close()


def command(args: list[str], *, capture: bool = True, timeout: int = 900) -> str:
    result = subprocess.run(args, cwd=ROOT, text=True, check=True, timeout=timeout,
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else ""


def counters(path: Path) -> dict[str, int]:
    return {key: int(value) for key, value in (line.split() for line in path.read_text().splitlines())}


def kernel_sample(pid: int) -> dict:
    group = next(line[3:] for line in Path(f"/proc/{pid}/cgroup").read_text().splitlines() if line.startswith("0::"))
    root = Path("/sys/fs/cgroup") / group.lstrip("/")
    memory = counters(root / "memory.stat")
    return {"cpu": counters(root / "cpu.stat"), "memory_current": int((root / "memory.current").read_text()),
            "memory_stat": {key: memory[key] for key in (
                "anon", "file", "kernel", "shmem", "sock", "slab", "pagetables", "file_mapped", "file_dirty")}}


def runtime_sample(port: int) -> dict:
    result = json.loads(http(port, "/bench/runtime"))
    for line in http(port, "/metrics").decode().splitlines():
        if line.startswith(("hoori_http_connections_active ", "hoori_http_requests_active ")):
            name, value = line.split()
            result[name] = int(value)
    # The pinned SDK has no poolStats API. Keep the missing measurement explicit.
    result["http_pool_pending_acquires"] = None
    return result


def resources(before: dict, after: dict, samples: list[dict], successes: int) -> dict:
    output = {}
    for role in ROLES:
        first, last = before[role], after[role]
        runtime = last["runtime"]
        delta = {key: runtime[key] - first["runtime"][key] for key in (
            "allocated_bytes", "object_allocations", "array_allocations", "gc_collections", "gc_pause_ns",
            "service_bytes_read", "service_bytes_written")}
        cpu_usec = last["kernel"]["cpu"]["usage_usec"] - first["kernel"]["cpu"]["usage_usec"]
        output[role] = {"before": first, "after": last, "runtime_delta": delta, "cpu_usec": cpu_usec,
                        "cpu_usec_per_success": cpu_usec / successes if successes else None,
                        "allocated_bytes_per_success": delta["allocated_bytes"] / successes if successes else None,
                        "memory_current_peak_sampled": max(
                            [first["kernel"]["memory_current"], last["kernel"]["memory_current"]]
                            + [sample[role]["memory_current"] for sample in samples])}
    output["system"] = {"cpu_usec": sum(output[role]["cpu_usec"] for role in ROLES),
                        "rss_bytes_after": sum(after[role]["runtime"]["rss_bytes"] for role in ROLES),
                        "guest_heap_bytes_after": sum(after[role]["runtime"]["heap_used_bytes"] for role in ROLES),
                        "memory_current_after": sum(after[role]["kernel"]["memory_current"] for role in ROLES)}
    return output


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def experiment(args, variant: str, repeat: int, work: Path, receipt: dict, document: dict) -> None:
    project = f"hoori-micro-bench-{os.getpid()}-{repeat}-{variant.lower()}"
    override = work / "compose.json"
    classpath = "/opt/bench:/opt/app/lib/hoori-micro-0.1.0-SNAPSHOT.jar:"
    classpath += ":".join("/opt/hoori/" + path for path in receipt["artifacts"] if path.endswith(".jar"))
    services = {}
    for i, role in enumerate(ROLES):
        services[role] = {"image": "hoori-micro-benchmark-build-" + role,
            "networks": ["edge", "backend"],
            "entrypoint": ["/opt/hoori/bin/hoori", "stats" if args.profile else "run", "--engine", args.engine,
            *(["--format", "json"] if args.profile else ["--live-output"]),
            "--graceful-signals", "--max-heap-bytes", str(args.heap_mib * 1048576),
            "--gc-threshold-bytes", "2097152", "--allow-environment-read", "--allow-network-listen",
            *([] if role == "registry" else ["--allow-network-connect", "--allow-host-resolution"]),
            "--class-path", classpath, "hoori/micro/BenchmarkMain"],
            "volumes": [f"{ROOT / 'framework/target/test-classes'}:/opt/bench:ro"],
            "cpus": args.cpus, "mem_limit": f"{args.memory_mib}m",
            "ports": [f"127.0.0.1:{args.port + i}:8080"],
            "environment": {"BENCH_ROLE": role, "BENCH_VARIANT": variant,
                "BENCH_CATALOG_INSTANCES": str(args.catalog_instances),
                "HOORI_ADVERTISE_URL": f"http://{role}:8080", "HOORI_INSTANCE_ID": role + "-bench",
                "HOORI_CLIENT_CONNECTIONS": str(args.pool), "HOORI_CLIENT_PER_ORIGIN": str(args.pool),
                "HOORI_CLIENT_TIMEOUT_MS": "2000", "HOORI_REQUEST_TIMEOUT_MS": "10000",
                "HOORI_CATALOG_MAX_AGE_MS": "3600000" if variant in "AB" else "30000"}}
    # Override the normal demo's gateway host port too; all benchmark ports remain on loopback.
    override.write_text(json.dumps({"services": services}))
    env = os.environ.copy()
    env["HOORI_DEMO_PORT"] = str(args.port + 3)
    compose = ["docker", "compose", "--project-name", project, "-f", str(ROOT / "compose.yaml"), "-f", str(override)]

    def dc(*parts):
        result = subprocess.run(compose + list(parts), cwd=ROOT, env=env, text=True, check=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=900)
        return result.stdout.strip()

    control_stop = threading.Event()
    control_failures = []

    def control():
        try:
            change = 0
            while not control_stop.is_set():
                for n in range(args.catalog_instances):
                    name = f"extra-{n}"
                    http(args.port, f"/v1/instances/{name}", "PUT", {
                        "id": name, "service": name, "version": 1, "url": "http://recipes:8080",
                        "actions": [{"name": "echo"}]})
                # One changing unrelated provider, using the actual registration protocol.
                http(args.port, "/v1/instances/changing", "PUT", {
                    "id": "changing", "service": "changing", "version": 1, "url": "http://recipes:8080",
                    "actions": [{"name": "echo" if change % 2 else "added"}]})
                change += 1
                control_stop.wait(2)
        except Exception as error:
            control_failures.append(type(error).__name__)

    controller = None
    result = {"variant": variant, "repeat": repeat, "phases": []}
    document["runs"].append(result)
    try:
        startup = time.monotonic()
        dc("up", "--no-build", "--detach", "--wait", "--wait-timeout", "180")
        result["startup_seconds"] = time.monotonic() - startup
        identities = {}
        for i, role in enumerate(ROLES):
            cid = dc("ps", "-q", role)
            container = json.loads(command(["docker", "inspect", cid]))[0]
            image = json.loads(command(["docker", "image", "inspect", container["Image"]]))[0]
            identities[role] = {"container": cid, "pid": container["State"]["Pid"], "image": image["Id"],
                "image_uncompressed_bytes": image["Size"],
                "limits": {key: container["HostConfig"][key] for key in (
                    "NanoCpus", "Memory", "MemorySwap", "ReadonlyRootfs", "CapDrop", "SecurityOpt", "PidsLimit")},
                "environment": container["Config"]["Env"]}
        result["containers"] = identities
        if variant in "CD":
            controller = threading.Thread(target=control)
            controller.start()

        def snapshot():
            return {role: {"runtime": runtime_sample(args.port + i),
                           "kernel": kernel_sample(identities[role]["pid"])} for i, role in enumerate(ROLES)}

        port, path = (args.port + 3, "/bench/meal") if variant == "D" else (args.port + 2, "/bench")
        # Readiness checks do not imply discovery convergence. Require the exact business output.
        payload = {"value": "x" * 64}
        ready_deadline = time.monotonic() + 120
        while True:
            try:
                if json.loads(http(port, path, "POST", payload)) == payload:
                    break
            except (OSError, RuntimeError, ValueError, HTTPException):
                pass
            if time.monotonic() >= ready_deadline:
                raise RuntimeError("Business path did not converge")
            time.sleep(.5)
        result["cold_idle"] = snapshot()
        load(port, path, payload, args.warmup, args.workers, args.rate, args.p99_ms, args.error_fraction)
        phases = [("closed-small", 64, 0, 0), ("open-large", 8192, args.rate, 0),
                  ("sustained", 64, args.rate, 0), ("slow", 64, args.rate, args.delay_ms),
                  ("burst", 64, args.burst_rate, args.delay_ms), ("recovery", 64, args.rate, 0)]
        for name, size, rate, delay in phases:
            if name == "recovery":
                time.sleep(args.idle)
                result["post_burst_idle"] = snapshot()
            before = snapshot()
            samples = []
            profile_samples = []
            sampling_errors = []
            sampling_stop = threading.Event()

            def sample():
                try:
                    while not sampling_stop.wait(1):
                        samples.append({role: kernel_sample(identities[role]["pid"]) for role in ROLES})
                        if args.profile:
                            profile_samples.append({role: runtime_sample(args.port + i) for i, role in enumerate(ROLES)})
                except Exception as error:
                    sampling_errors.append(error)

            sampler = threading.Thread(target=sample)
            sampler.start()
            try:
                outcome = load(port, path, {"value": "x" * size, "delayMillis": delay}, args.seconds,
                               args.workers, rate, args.p99_ms, args.error_fraction)
            finally:
                sampling_stop.set()
                sampler.join()
            if sampling_errors:
                raise sampling_errors[0]
            after = snapshot()
            outcome.update({"name": name, "payload_string_bytes": size, "delay_ms": delay, "rate": rate,
                            "resources": resources(before, after, samples, outcome["successful"])})
            if args.profile:
                outcome["profile_samples"] = profile_samples
            result["phases"].append(outcome)
            args.output.write_text(json.dumps(document, indent=2) + "\n")
            print(f"{variant}/{repeat}/{name}: success={outcome['successful']} fail={outcome['failed']} "
                  f"not_started={outcome['not_started']} p99={outcome['p99_ms']}", flush=True)
        time.sleep(args.idle)
        result["final_idle"] = snapshot()
        result["control_failures"] = control_failures
        if control_failures:
            raise RuntimeError("Catalog controller failed")
        if args.profile:
            dc("stop")
            result["profiles"] = {role: dc("logs", "--no-color", "--no-log-prefix", role) for role in ROLES}
        result["complete"] = True
    except Exception:
        result["complete"] = False
        print(dc("logs", "--no-color", "--tail", "60"), flush=True)
        raise
    finally:
        control_stop.set()
        if controller is not None:
            controller.join(timeout=20)
        dc("down", "--remove-orphans")
        args.output.write_text(json.dumps(document, indent=2) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--variants", default="ABCD")
    parser.add_argument("--engine", choices=("mixed", "interpreter"), default="mixed")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--seconds", type=float, default=10)
    parser.add_argument("--warmup", type=float, default=10)
    parser.add_argument("--idle", type=float, default=5)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--pool", type=int, default=4)
    parser.add_argument("--rate", type=float, default=4)
    parser.add_argument("--burst-rate", type=float, default=64)
    parser.add_argument("--delay-ms", type=int, default=250)
    parser.add_argument("--catalog-instances", type=int, default=0)
    parser.add_argument("--cpus", type=float, default=.5)
    parser.add_argument("--memory-mib", type=int, default=256)
    parser.add_argument("--heap-mib", type=int, default=32)
    parser.add_argument("--port", type=int, default=18100)
    parser.add_argument("--seed", type=int, default=11)
    parser.add_argument("--p99-ms", type=float, default=1000)
    parser.add_argument("--error-fraction", type=float, default=.01)
    parser.add_argument("--profile", action="store_true", help="Separate instrumented stats run, not timing evidence")
    args = parser.parse_args()
    if (not args.variants or any(variant not in "ABCD" for variant in args.variants)
            or len(set(args.variants)) != len(args.variants) or args.repeats < 1 or args.seconds <= 0
            or args.warmup <= 0 or args.idle < 0 or not 1 <= args.workers <= 128 or not 1 <= args.pool <= 32
            or not 0 <= args.catalog_instances <= 128 or not 0 <= args.delay_ms <= 1000
            or args.rate <= 0 or args.burst_rate <= 0 or args.cpus <= 0 or args.memory_mib <= 0
            or args.heap_mib <= 2 or not 1024 <= args.port <= 65531 or args.p99_ms <= 0
            or not 0 <= args.error_fraction <= 1):
        parser.error("Invalid benchmark bounds")
    receipt = verify(ROOT / ".docker-context/runtime")
    if receipt["runtime"]["profile"] != "release":
        parser.error("Benchmark requires a verified release distribution")
    fixture = ROOT / "framework/target/test-classes/hoori/micro/BenchmarkMain.class"
    if not fixture.exists():
        parser.error("Run scripts/build.sh first to compile the benchmark fixture")
    framework = ROOT / "framework/target/hoori-micro-0.1.0-SNAPSHOT.jar"
    if any(digest(framework) != digest(ROOT / f".docker-context/apps/{role}/lib/{framework.name}") for role in ROLES):
        parser.error("Staged framework differs from the build; run scripts/build.sh again")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists():
        parser.error("Output already exists; choose a new file")
    settings = {key: str(value) if isinstance(value, Path) else value for key, value in vars(args).items()}
    document = {"schema": 1, "settings": settings, "runtime_receipt": receipt,
                "commit": command(["git", "rev-parse", "HEAD"]),
                "git_status": command(["git", "status", "--short"]),
                "fixture_sha256": digest(fixture), "load_script_sha256": digest(Path(__file__)),
                "framework_jar_sha256": digest(ROOT / "framework/target/hoori-micro-0.1.0-SNAPSHOT.jar"),
                "docker_version": command(["docker", "version", "--format", "{{.Server.Version}}"]),
                "compose_version": command(["docker", "compose", "version", "--short"]), "runs": []}
    document["measurement_mode"] = "profile" if args.profile else "timing"
    order = [(variant, repeat) for repeat in range(args.repeats) for variant in args.variants]
    random.Random(args.seed).shuffle(order)
    document["order"] = order
    # Persist the acceptance limits before any measured request, and each finished fresh-process run.
    args.output.write_text(json.dumps(document, indent=2) + "\n")
    command(["docker", "compose", "--project-name", "hoori-micro-benchmark-build", "build"])
    with tempfile.TemporaryDirectory(prefix="hoori-micro-benchmark-") as directory:
        for variant, repeat in order:
            experiment(args, variant, repeat, Path(directory), receipt, document)
            args.output.write_text(json.dumps(document, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
