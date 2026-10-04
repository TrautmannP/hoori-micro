#!/usr/bin/env python3
"""Focused Micro/Jdbi requests with the upstream disposable database and real PostgreSQL fault peer."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from http.client import HTTPConnection
import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time

from optional_example import classpath, configuration
from runtime_check import ROOT, runtime_classpath, verify
from stage import jar


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hoori-checkout", type=Path, default=ROOT.parent / "hoori")
    args = parser.parse_args()
    runtime = ROOT / ".docker-context/runtime"
    config, lock = configuration("local-data")
    receipt = verify(runtime, lock)
    upstream = args.hoori_checkout.resolve()
    # Reuse exactly the pinned fixture code, without copying SDK/driver implementations.
    fixture_hashes = {}
    for name in ("jdbc_test.py", "jdbi_test.py", "jdbc_peers.py", "jdbc_tls.py"):
        path = upstream / "scripts" / name
        pinned = subprocess.check_output(["git", "show", receipt["source"]["revision"] + ":scripts/" + name], cwd=upstream)
        assert path.read_bytes() == pinned, "Upstream fixture differs from the selected runtime: " + name
        fixture_hashes[name] = hashlib.sha256(pinned).hexdigest()
    sys.path.insert(0, str(upstream / "scripts"))
    from jdbi_test import ARTIFACTS, database
    from jdbc_test import IMAGE, sql, wait_sql
    from jdbc_peers import FaultPeers

    engine = os.environ.get("HOORI_ENGINE", "mixed")
    output = ROOT / ".cache" / ("data-" + engine)
    output.mkdir(parents=True, exist_ok=True)
    data_cp = classpath("local-data", runtime, receipt, lock)
    hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in data_cp}
    for _, name, version, expected in ARTIFACTS:
        if f"{name}-{version}.jar" in hashes:
            assert hashes[f"{name}-{version}.jar"] == expected, name
    assert all("hoori-task-annotations" not in p.name and "hoori-task-processor" not in p.name for p in data_cp)
    data_cp += [ROOT / "examples/local-data/target/test-classes"]
    core_cp = [jar("framework", "hoori-micro"), jar("examples/demo-contracts", "hoori-micro-demo-contracts")]
    core_cp += [jar(f"examples/{role}-service", role + "-service") for role in ("recipes", "pantry", "shopping")]
    core_cp += [ROOT / "examples/shopping-service/target/test-classes", *runtime_classpath(runtime, receipt)]
    evidence = {"runtime": receipt["source"], "engine": engine, "classpath_sha256": hashes,
                "fixtures_sha256": fixture_hashes, "postgres_image": IMAGE, "checks": [], "recovery": []}
    evidence["probe_sha256"] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in
        [Path(__file__), ROOT / "examples/local-data/target/test-classes/dev/hoori/micro/data/DataChecks.class"]}
    ports, processes, logs = {}, {}, {}
    for role in ("registry", "recipes", "pantry", "data"):
        with socket.socket() as free:
            free.bind(("127.0.0.1", 0))
            ports[role] = free.getsockname()[1]

    def request(role, path, body=None, headers=None):
        connection = HTTPConnection("127.0.0.1", ports[role], timeout=35)
        try:
            connection.request("GET" if body is None else "POST", path,
                None if body is None else json.dumps(body).encode(),
                {"Content-Type": "application/json", **(headers or {})})
            response = connection.getresponse()
            return response.status, response.read()
        finally:
            connection.close()

    def until(check, message):
        end = time.monotonic() + 25
        while time.monotonic() < end:
            assert all(p.poll() is None for p in processes.values()), "Service exited: " + message
            try:
                if check():
                    return
            except (OSError, ConnectionError):
                pass
            time.sleep(.02)
        raise AssertionError(message)

    def probe(role="data"):
        status, body = request(role, "/probe")
        assert status == 200, (role, status, body)
        return json.loads(body)

    def gate(role, opened):
        assert request(role, "/gate/" + ("open" if opened else "closed"), {})[0] == 200

    def invoke(identifier, action="save", recipe=1):
        return request("data", "/_hoori/invoke", {"id": identifier, "recipeId": recipe},
                       {"X-Hoori-Action": "drafts." + action, "X-Hoori-Version": "1", "X-Request-ID": "save-" + str(identifier)})

    def case(mode, identifier):
        return request("data", "/case/" + mode, {"id": identifier, "recipeId": 1}, {"X-Request-ID": "data-" + mode})

    def metrics():
        status, body = request("data", "/metrics")
        assert status == 200
        return {line.split()[0]: float(line.split()[1]) for line in body.decode().splitlines()
                if line.startswith(("hoori_micro_tasks_", "hoori_micro_handles_", "hoori_micro_heap_"))}

    def stop(role):
        process = processes.pop(role)
        process.send_signal(signal.SIGTERM)
        assert process.wait(timeout=20) == 0, role
        text = Path(logs[role].name).read_text()
        assert "roots_drained=true drained=true" in text, role
        if role == "data":
            assert "data_factory_closed active=0" in text

    env, db = os.environ.copy(), {}
    holder = None
    try:
        with database(output, env, False, details=db) as database_port, FaultPeers(int(database_port)) as peers:
            sql(db["name"], (ROOT / "examples/local-data/schema.sql").read_text())

            def rows(identifier):
                return sql(db["name"], f"select (select count(*) from meal_drafts where id={identifier}),"
                           f"(select count(*) from meal_outbox where id={identifier})").strip()

            def blocked_query():
                return sql(db["name"], "select count(*) from pg_stat_activity where wait_event='advisory' "
                           "and query like '%pg_advisory_xact_lock(724199)%'").strip() == "1"

            for role in ports:
                runtime_env = env | {"PATH": "/nonexistent", "JAVA_HOME": "/nonexistent",
                    "HOORI_BIND_ADDRESS": "127.0.0.1", "HOORI_PORT": str(ports[role]), "HOORI_INSTANCE_ID": role,
                    "HOORI_ADVERTISE_URL": f"http://127.0.0.1:{ports[role]}", "COMPOSITION_ROLE": role,
                    "HOORI_REGISTRY_URL": f"http://127.0.0.1:{ports['registry']}", "HOORI_HEARTBEAT_MS": "100",
                    "HOORI_REGISTRY_TTL_MS": "2000", "HOORI_CLIENT_TIMEOUT_MS": "10000", "HOORI_WORK_TIMEOUT_MS": "25000",
                    "HOORI_REQUEST_TIMEOUT_MS": "30000", "HOORI_SHUTDOWN_GRACE_MS": "400",
                    "HOORI_DB_URL": f"jdbc:postgresql://127.0.0.1:{peers.proxy_port}/hoori_jdbc",
                    "HOORI_DB_USER": "hoori_jdbc", "HOORI_DB_PASSWORD": env["HOORI_JDBC_TEST_PASSWORD"], "HOORI_DB_SSLMODE": "disable"}
                main = "dev/hoori/micro/data/DataChecks" if role == "data" else (
                    "hoori/micro/Registry" if role == "registry" else "dev/hoori/micro/demo/CompositionMain")
                cp = data_cp if role == "data" else core_cp
                log = (output / (role + ".log")).open("w")
                logs[role] = log
                processes[role] = subprocess.Popen([str(runtime / "bin/hoori"), "run", "--engine", engine, "--live-output",
                    "--graceful-signals", "--max-heap-bytes", "33554432", "--allow-environment-read", "--allow-network-listen",
                    "--allow-network-connect", *(config["capabilities"] if role == "data" else ["--allow-resource-read"]),
                    "--class-path", ":".join(map(str, cp)), main], env=runtime_env, stdout=log, stderr=log)
                until(lambda: request(role, "/health/ready")[0] == 200, role + " ready")
            until(lambda: request("data", "/discovery")[0] == 200, "read-only discovery readiness")
            opened = probe()["opened"]
            remote_calls = {role: probe(role)["calls"] for role in ("recipes", "pantry")}
            for action in ("save", "save-scoped"):
                status, raw = invoke(0, action, recipe=0)
                assert status == 400 and json.loads(raw) == {"code": "validation_failed", "violations": [
                    {"path": "id", "code": "positive"}, {"path": "recipeId", "code": "positive"}]}, (status, raw)
            assert probe()["opened"] == opened and rows(0) == "0|0"
            assert all(probe(role)["calls"] == remote_calls[role] for role in remote_calls)
            evidence["checks"].append("generated Draft validation rejects both IDs before remote reads and local DB acquisition")
            assert invoke(1)[0] == 200 and rows(1) == "1|1"
            assert invoke(3, "save-scoped")[0] == 200 and rows(3) == "1|1"
            assert invoke(3, "save-scoped")[0] == 500 and rows(3) == "1|1", "Generated boundary must roll back a SQL constraint failure"
            assert invoke(4, recipe=999)[0] == 502 and rows(4) == "0|0"
            evidence["checks"].append("real Micro actions: explicit and generated local data/outbox commit; remote failure writes nothing")

            with ThreadPoolExecutor(max_workers=3) as pool:
                before = probe()["opened"]
                for role in ("recipes", "pantry"):
                    gate(role, False)
                prepared = pool.submit(invoke, 2)
                until(lambda: all(probe(role)["active"] == 1 for role in ("recipes", "pantry")), "remote reads overlap before DB")
                assert probe()["opened"] == before and not prepared.done()
                for role in ("recipes", "pantry"):
                    gate(role, True)
                assert prepared.result()[0] == 200 and rows(2) == "1|1"
                evidence["checks"].append("both discovered remote reads start before any local DB acquisition")

                for mode, identifier, status in (("body", 10, 409), ("nested", 11, 500), ("deadline", 12, 504)):
                    response = case(mode, identifier)
                    assert response[0] == status, (mode, response, probe())
                    assert probe()["active"] == 0 and probe()["status"] == "ROLLED_BACK" and rows(identifier) == "0|0"
                before = probe()["released"]
                gate("data", False)
                child = pool.submit(case, "child", 15)
                until(lambda: probe()["childWaiting"], "child finally blocks transaction completion")
                assert not child.done() and probe()["active"] == 1 and probe()["released"] == before
                gate("data", True)
                assert child.result()[0] == 500 and probe()["childFinished"] and rows(15) == "0|0"
                assert case("foreign", 13)[0] == 200 and rows(13) == "1|1" and rows(1013) == "0|0"
                assert case("independent", 14)[0] == 500 and rows(14) == "0|0" and rows(1014) == "1|1"
                evidence["checks"].append("body/required-child/deadline/caught REQUIRED errors roll back after child drain; foreign Handle fails before SQL; independent child commit survives parent rollback")

                for mode, identifier in (("committed", 16), ("encode", 17)):
                    assert case(mode, identifier)[0] == 500 and probe()["status"] == "COMMITTED" and rows(identifier) == "1|1"
                gate("data", False)
                overlap = [pool.submit(case, "overlap", identifier) for identifier in (18, 19)]
                until(lambda: probe()["active"] == 2, "two independently owned DB connections")
                gate("data", True)
                responses = [work.result() for work in overlap]
                assert all(status == 200 for status, _ in responses)
                assert len({json.loads(body)["pid"] for _, body in responses}) == 2
                assert rows(18) == rows(19) == "1|1"

                before = probe()
                peers.drop_commit.set()
                assert case("ok", 20)[0] == 500
                after = probe()
                assert peers.commit_dropped.is_set() and after["status"] == "UNKNOWN" and rows(20) == "1|1"
                assert after["active"] == 0 and after["opened"] == before["opened"] + 1
                assert after["discarded"] == before["discarded"] + 1 and after["released"] == before["released"]
                evidence["checks"].append("separate overlapping handles; confirmed commit survives notification/encoding failure; lost acknowledgement is UNKNOWN, physically discarded and never replayed")

                holder = subprocess.Popen(["docker", "exec", "-e", "PGAPPNAME=hoori-micro-holder", db["name"],
                    "psql", "-XqAt", "-U", "hoori_jdbc", "-d", "hoori_jdbc", "-c",
                    "select pg_advisory_lock(724199); select pg_sleep(900)"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                wait_sql(db["name"], "select exists(select 1 from pg_locks where locktype='advisory' and objid=724199 and granted)", holder)
                baseline = metrics()
                for cycle in range(3):
                    identifier = 30 + cycle
                    work = pool.submit(case, "query", identifier)
                    until(blocked_query, "query really blocked in PostgreSQL")
                    assert request("data", "/cancel", {})[0] == 200
                    response = work.result()
                    snapshot = probe()
                    # Jdbi preserves the primary driver exception; the DB-free core exposes a safe 500.
                    assert response[0] == 500 and snapshot["status"] == "ROLLED_BACK" and snapshot["queryCancelled"], (response, snapshot)
                    assert snapshot["active"] == 0 and not blocked_query() and rows(identifier) == "0|0"
                    assert invoke(130 + cycle)[0] == 200 and rows(130 + cycle) == "1|1"
                    assert request("data", "/gc", {})[0] == 200
                    time.sleep(.3)
                    assert request("data", "/gc", {})[0] == 200
                    current, snapshot = metrics(), probe()
                    assert snapshot["retained"] == 0, snapshot
                    for name in ("hoori_micro_tasks_active", "hoori_micro_handles_open"):
                        assert current[name] <= baseline[name] + 2, (baseline, current)
                    evidence["recovery"].append({"status": response[0], "factory": snapshot, "runtime": current})
                evidence["checks"].append("three real Statement.cancel/SQLSTATE 57014 cycles drain before rollback/return; subsequent requests work, closed connections collect and task/handle counts recover")

                shutdown = pool.submit(case, "query", 99)
                until(blocked_query, "query before SIGTERM")
                stop("data")
                try:
                    shutdown.result()
                except OSError:
                    pass  # Transport may close after its expired grace; DB drain is checked independently.
                assert rows(99) == "0|0" and not blocked_query()
                text = Path(logs["data"].name).read_text()
                assert "transaction=UNKNOWN" in text and "transaction=COMMITTED" in text and "transaction=ROLLED_BACK" in text
                assert "data_status=ROLLED_BACK query_cancelled=true" in text
                evidence["checks"].append("SIGTERM cancels blocked query, completes rollback and closes service resources with zero active connections")
            for role in ("recipes", "pantry", "registry"):
                stop(role)
            sql(db["name"], "select pg_terminate_backend(pid) from pg_stat_activity where application_name='hoori-micro-holder'")
            holder.communicate(timeout=10)
            holder = None
            evidence["fault_peer"] = peers.observed
            (output / "results.json").write_text(json.dumps(evidence, indent=2) + "\n")
            print("PASS: local transactions, generated boundary, cancellation/status/recovery and shutdown;", output)
    finally:
        for process in processes.values():
            if process.poll() is None:
                process.kill()
            process.wait(timeout=5)
        if holder is not None:
            holder.communicate(timeout=10)
        for log in logs.values():
            log.close()


if __name__ == "__main__":
    main()
