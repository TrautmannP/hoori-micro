#!/usr/bin/env python3
"""Real pinned guests: transitive budgets, pre-write pool sampling and prepared gateway bounds."""
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import threading
import time

from runtime_check import ROOT, runtime_classpath, verify


def main():
    runtime = ROOT / ".docker-context/runtime"
    receipt = verify(runtime)
    fingerprints = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in (
        ROOT / "framework/target/test-classes/hoori/micro/BudgetMain.class",
        ROOT / "framework/target/hoori-micro-0.1.0-SNAPSHOT.jar", Path(__file__))}
    instances, processes, observations, wire = {}, {}, [], []
    lock = threading.Lock()
    revision = 1

    class Peer(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def reply(self):
            nonlocal revision
            raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            status = 200
            with lock:
                if self.command == "PUT":
                    instance = json.loads(raw)
                    instances[instance["id"]] = instance
                    revision += 1
                elif self.command == "DELETE":
                    instances.pop(self.path.split("/")[-1], None)
                    revision += 1
                    status = 204
                selected = deepcopy(list(instances.values()))
                current = revision
            view = self.headers.get("X-Hoori-Catalog-View", "all")
            if view == "none":
                selected = []
            elif view.startswith("services="):
                dependencies = view[len("services="):].split(",")
                selected = [i for i in selected if f"{i['service']}:{i['version']}" in dependencies]
                for i in selected:
                    i["endpoints"] = [{k: v for k, v in endpoint.items() if k != "permission"} for endpoint in i["endpoints"]]
            elif view == "public":
                for i in selected:
                    i["endpoints"] = [a for a in i["endpoints"] if "permission" in a]
                selected = [i for i in selected if i["endpoints"]]
            body = json.dumps({"epoch": "budget-test", "revision": current, "complete": True,
                               "instances": selected}).encode() if status != 204 else b""
            self.send_response(status)
            for name, value in {"Content-Type": "application/json", "Content-Length": str(len(body)),
                "X-Hoori-Catalog-Epoch": "budget-test",
                "X-Hoori-Catalog-Revision": str(current), "X-Hoori-Catalog-View": view}.items():
                self.send_header(name, value)
            self.end_headers()
            try:
                self.wfile.write(body)
            except OSError:
                pass

        def do_GET(self):
            if self.path.startswith("/_hoori/"):
                return self.reply()
            wire.append({"path": self.path, "budget": self.headers.get("X-Hoori-Budget-Ms"),
                         "port": self.client_address[1]})
            if self.path.startswith("/hold"):
                time.sleep(.8)
            body = json.dumps({"wire": self.headers.get("X-Hoori-Budget-Ms")}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            if self.path == "/hold-close":
                self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)

        do_POST = do_PUT = do_DELETE = reply

        def log_message(self, *args):
            pass

    ports = {}
    sockets = []
    for role in ("recipes", "shopping", "gateway"):
        available = socket.socket()
        available.bind(("127.0.0.1", 0))
        sockets.append(available)
        ports[role] = available.getsockname()[1]
    for available in sockets:
        available.close()

    def request(role, path, payload=None, fields=()):
        body = None if payload is None else json.dumps(payload).encode()
        connection = HTTPConnection("127.0.0.1", ports[role], timeout=8)
        try:
            connection.putrequest("GET" if body is None else "POST", path)
            if body is not None:
                connection.putheader("Content-Type", "application/json")
                connection.putheader("Content-Length", str(len(body)))
            for name, value in fields:
                connection.putheader(name, value)
            connection.endheaders(body)
            response = connection.getresponse()
            return response.status, response.read()
        finally:
            connection.close()

    def probe(role):
        status, body = request(role, "/probe")
        assert status == 200, (role, status, body)
        value = json.loads(body)
        assert value["outgoing_active"] <= 2 and value["outgoing_pending"] <= 2
        assert value["pool_pending"] == 0
        return value

    def until(test):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            assert all(p.poll() is None for p in processes.values()), "Native service exited"
            try:
                if test():
                    return
            except OSError:
                pass
            time.sleep(.02)
        raise AssertionError("Native budget/gateway state did not converge")

    def invoke_headers(value):
        return [("X-Hoori-Endpoint", "POST /chain application/json application/json"), ("X-Hoori-Version", "1"),
                ("X-Hoori-Budget-Ms", value)]

    def serial_answer(result, maximum):
        status, body = result
        assert status == 200, (status, body)
        value = json.loads(body)
        first, second = value["first"], value["second"]
        assert 0 < second["wire"] < first["wire"] <= maximum, value
        assert first["wire"] - second["wire"] >= 200, value
        assert first["remaining"] < first["wire"] and second["remaining"] < second["wire"], value
        assert not first["authorization"] and not second["authorization"]
        observations.append({"serial": value, "maximum": maximum})
        return value

    def update(change):
        nonlocal revision
        with lock:
            change()
            revision += 1
            return revision

    with ThreadingHTTPServer(("127.0.0.1", 0), Peer) as peer:
        server = threading.Thread(target=peer.serve_forever)
        server.start()
        env = os.environ.copy()
        engine = env.get("HOORI_ENGINE", "mixed")
        cp = ":".join([str(ROOT / "framework/target/test-classes"), str(ROOT / "framework/target/classes")]
                      + list(map(str, runtime_classpath(runtime, receipt))))
        command = [str(runtime / "bin/hoori"), "run", "--engine", engine, "--live-output", "--graceful-signals",
                   "--max-heap-bytes", "33554432", "--allow-environment-read", "--allow-network-listen",
                   "--allow-network-connect", "--class-path", cp, "hoori/micro/BudgetMain"]
        env.update({"HOORI_BIND_ADDRESS": "127.0.0.1", "HOORI_REGISTRY_URL": f"http://127.0.0.1:{peer.server_port}",
                    "HOORI_HEARTBEAT_MS": "100", "HOORI_CATALOG_MAX_AGE_MS": "1000", "HOORI_BODY_BYTES": "262144",
                    "HOORI_OUTGOING_CALLS": "2", "HOORI_OUTGOING_PENDING_CALLS": "2",
                    "HOORI_CLIENT_CONNECTIONS": "2", "HOORI_CLIENT_PER_ORIGIN": "2",
                    "HOORI_CLIENT_TIMEOUT_MS": "2000", "HOORI_REQUEST_TIMEOUT_MS": "5000",
                    "HOORI_CLIENT_IDLE_MS": "500", "BUDGET_PEER": f"http://127.0.0.1:{peer.server_port}"})
        try:
            for hold in ("/hold", "/hold-close"):
                start = len(wire)
                run = subprocess.run(command, env=dict(env, BUDGET_ROLE="pool", BUDGET_HOLD_PATH=hold),
                                     text=True, capture_output=True, timeout=15)
                assert run.returncode == 0 and "pool_closed=true" in run.stdout, (run.stdout, run.stderr)
                events = wire[start:]
                assert [e["path"] for e in events] == ["/warm", hold, "/observe"], events
                assert 0 < int(events[-1]["budget"]) <= 1200, events
                assert (events[1]["port"] == events[2]["port"]) == (hold == "/hold"), events
                observations.append({"pool": events, "stdout": run.stdout})
            for role, port in ports.items():
                role_env = dict(env, BUDGET_ROLE=role, HOORI_PORT=str(port), HOORI_INSTANCE_ID=role,
                                HOORI_ADVERTISE_URL=f"http://127.0.0.1:{port}")
                processes[role] = subprocess.Popen(command, env=role_env, text=True,
                                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            until(lambda: "/chain" in probe("gateway")["routes"])
            # Compile/warm all real hops before the timing assertions.
            serial_answer(request("gateway", "/chain", {}), 2000)
            status, body = request("gateway", "/observe/1", {"id": None})
            assert status == 200 and json.loads(body)["body"] == {"id": None}, (status, body)
            assert request("gateway", "/observe/%ff", {})[0] == 400
            before = probe("recipes")["calls"]
            for bad in ("", "-1", "01", "1.0", "600001", "999999999", "bad"):
                assert request("shopping", "/chain", {}, invoke_headers(bad))[0] == 400
            assert request("shopping", "/chain", {}, invoke_headers("1000")
                           + [("x-hoori-budget-ms", "500")])[0] == 400
            assert request("shopping", "/chain", {}, invoke_headers("0"))[0] == 504
            assert probe("recipes")["calls"] == before
            serial_answer(request("shopping", "/chain", {}, invoke_headers("1000")), 1000)
            serial_answer(request("shopping", "/chain", {}, invoke_headers("600000")), 2000)
            for malicious in (("X-Hoori-Budget-Ms", "0"), ("X-Hoori-Budget-Ms", "bad")):
                value = serial_answer(request("gateway", "/chain", {}, [malicious, ("X-Hoori-Budget-Ms", "600000"),
                        ("X-Hoori-Endpoint", "wrong endpoint"), ("X-Hoori-Version", "999"),
                        ("Authorization", "Bearer not-forwarded"), ("X-Request-ID", "native-chain")]), 2000)
                assert value["first"]["requestId"] == value["second"]["requestId"] == "native-chain"
            serial_answer(request("shopping", "/root", {}), 1000)
            before = probe("recipes")["calls"]
            assert request("shopping", "/expired", {}, [("X-Hoori-Endpoint", "POST /expired application/json application/json"),
                    ("X-Hoori-Version", "1"), ("X-Hoori-Budget-Ms", "1000")])[0] == 504
            assert probe("recipes")["calls"] == before, "Expired context started another child"
            assert request("shopping", "/cancel", {}) == (200, b"cancelled")
            assert request("gateway", "/observe/1", {})[0] == 200, "Cancellation closed other calls' client"
            for _ in range(2):
                with ThreadPoolExecutor(max_workers=4) as executor:
                    calls = [executor.submit(request, "gateway", "/observe/1", {"delay": 1200}) for _ in range(4)]
                    until(lambda: probe("gateway")["outgoing_pending"] == 2)
                    assert sorted(call.result()[0] for call in calls) == [200, 200, 504, 504]
                until(lambda: all(probe(role)["incoming_active"] == probe(role)["outgoing_active"] == 0 for role in ports))
                assert request("gateway", "/observe/1", {})[0] == 200
                observations.append({"recovery": {role: probe(role) for role in ports}})

            # Native routing boundaries: removal, policy, rolling metadata conflict, count and byte overflow.
            new_revision = update(lambda: instances["recipes"]["endpoints"][1].update(path="/changed/{id}"))
            until(lambda: probe("gateway")["revision"] == new_revision)
            assert request("gateway", "/observe/1", {})[0] == 404
            assert request("gateway", "/changed/1", {})[0] == 404  # Provider does not serve a fabricated template.
            new_revision = update(lambda: instances["recipes"]["endpoints"][1].update(permission="recipes:admin"))
            until(lambda: probe("gateway")["revision"] == new_revision)
            assert request("gateway", "/changed/1", {})[0] == 403
            other = deepcopy(instances["recipes"])
            other.update(id="old-recipes")
            other["endpoints"][1].update(path="/changed/{id}", permission="recipes:read")
            new_revision = update(lambda: instances.update({"old-recipes": other}))
            until(lambda: probe("gateway")["revision"] == new_revision)
            assert request("gateway", "/changed/1", {})[0] == request("gateway", "/old/1", {})[0] == 404
            update(lambda: instances.pop("old-recipes"))
            new_revision = update(lambda: instances["recipes"]["endpoints"][1].update(path="/observe/{id}", permission="recipes:read"))
            until(lambda: probe("gateway")["revision"] == new_revision)
            for kind in ("count", "bytes"):
                previous = probe("gateway")["revision"]
                extras = {}
                for n, count in enumerate((128, 128, 1) if kind == "count" else (100, 100)):
                    name = f"extra-{n}" + ("x" * 50 if kind == "bytes" else "")
                    extras[name] = {"id": name, "service": name, "version": 1, "url": f"http://127.0.0.1:{ports['recipes']}",
                        "endpoints": [{"method": "POST", "consumes": "application/json", "produces": "application/json",
                                     "path": f"/extra/{n}/{i}/" + ("y" * 230 if kind == "bytes" else "x"),
                                     "permission": name + ":" + ("r" * 63 if kind == "bytes" else "read")} for i in range(count)]}
                first_name = next(iter(extras))
                accepted_revision = update(lambda: instances.update({first_name: extras[first_name]}))
                until(lambda: probe("gateway")["revision"] == accepted_revision)
                assert len(probe("gateway")["routes"]) == len(extras[first_name]["endpoints"]) + 2
                update(lambda: instances.update(extras))
                until(lambda: probe("gateway")["revision"] == 0)
                assert request("gateway", "/observe/1", {})[0] == 404, "Overflow/expired routes stayed public"
                new_revision = update(lambda: [instances.pop(key) for key in extras])
                until(lambda: probe("gateway")["revision"] == new_revision)
                assert new_revision > previous and request("gateway", "/observe/1", {})[0] == 200
                observations.append({"gateway_bound": kind, "accepted_revision": accepted_revision,
                                     "accepted_extra_routes": len(extras[first_name]["endpoints"]),
                                     "expired_revision": 0, "recovered_revision": new_revision})
            assert request("gateway", "/_hoori/invoke", {})[0] == 404
            observations.append({"final": {role: probe(role) for role in ports}})
            outputs = {}
            for role in ("gateway", "shopping", "recipes"):
                process = processes[role]
                process.send_signal(signal.SIGTERM)
                stdout, stderr = process.communicate(timeout=10)
                assert process.returncode == 0 and "budget_closed=true pools_closed=true" in stdout, (role, stdout, stderr)
                outputs[role] = {"pid": process.pid, "stdout": stdout, "stderr": stderr, "exit": process.returncode}
            print(json.dumps({"engine": engine, "runtime": receipt, "fingerprints": fingerprints,
                              "observations": observations, "outputs": outputs}, sort_keys=True))
            print("PASS: native pool wire sampling, serial hops, validation, cancellation, recovery and prepared gateway bounds")
        finally:
            for role, process in processes.items():
                if process.poll() is None:
                    process.send_signal(signal.SIGTERM)
                    try:
                        stdout, stderr = process.communicate(timeout=10)
                        print(role, stdout, stderr)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.communicate()
            peer.shutdown()
            server.join()


if __name__ == "__main__":
    main()
