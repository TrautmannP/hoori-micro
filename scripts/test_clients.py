#!/usr/bin/env python3
"""Independent source-free MVC consumer/provider: encoding, response filters and lost writes."""
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import tempfile
import threading
import time

from runtime_check import ROOT, maven_repository, runtime_classpath, verify
from stage import jar


IMPORTS = '''import hoori.micro.app.*;
import hoori.rest.mvc.*;
import java.util.List;
'''


def build(mvn, root, name, sources):
    work = root / name
    source = work / "src/main/java" / name
    source.mkdir(parents=True)
    (work / "pom.xml").write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<parent><groupId>dev.hoori</groupId><artifactId>hoori-micro-starter</artifactId><version>0.1.0-SNAPSHOT</version><relativePath/></parent>
<groupId>independent</groupId><artifactId>''' + name + '''</artifactId><version>1</version></project>''')
    for file, code in sources.items():
        (source / (file + ".java")).write_text("package " + name + ";\n" + IMPORTS + code)
    subprocess.run(mvn + ["package"], cwd=work, check=True)
    shutil.rmtree(work / "src")
    return work / "target" / (name + "-1.jar")


def main():
    runtime = ROOT / ".docker-context/runtime"
    receipt = verify(runtime)
    mvn = ["mvn", "-B", "-ntp", f"-Dmaven.repo.local={maven_repository(runtime)}"]
    subprocess.run(mvn + ["-pl", "processor,starter", "-am", "install", "-DskipTests", "-Dspotless.skip=true"], cwd=ROOT, check=True)
    work = Path(tempfile.mkdtemp(prefix="hoori-micro-clients-"))
    common = {"Echo": "public record Echo(String name, List<String> tags, String header) {}",
              "Write": "public record Write(String title) {}"}
    provider = build(mvn, work, "provider", common | {
        "Application": '''@MicroApplication(name="peer") public class Application {
 public static void main(String[] args) throws Exception { Micro.run(Application.class,args); }
}''',
        "Web": '''@RestController public class Web {
 private int writes;
 @GetMapping("/echo/{name}") @GatewayRoute(permission="peer:read")
 public Echo echo(@PathVariable("name") String name, @RequestParam(value="tag",required=false,max=4) List<String> tags) {
  return new Echo(name,tags,"");
 }
 @GetMapping("/header")
 public Echo header(@RequestHeader("X-Mode") String mode) { return new Echo("",List.of(),mode); }
 @PostMapping("/write") @GatewayRoute(permission="peer:write")
 public HttpResult<Write> write(@RequestBody Write body) { writes++; return HttpResult.of(201,body).header("Location","/write/"+writes); }
 @GetMapping("/writes") public int writes() { return writes; }
 @GetMapping("/redirect") @GatewayRoute(permission="peer:read")
 public HttpResult<Write> redirect() { return HttpResult.<Write>empty(302).header("Location","/writes"); }
 @GetMapping("/external") @GatewayRoute(permission="peer:read")
 public HttpResult<Write> external() { return HttpResult.of(200,new Write("ok")).header("Location","http://private.invalid/secret"); }
}'''})
    consumer = build(mvn, work, "consumer", common | {
        "Application": '''@MicroApplication(name="consumer") public class Application {
 public static void main(String[] args) throws Exception { Micro.run(Application.class,args); }
}''',
        "Peer": '''@ServiceClient(name="peer") public interface Peer {
 @GetMapping("/echo/{name}") Echo echo(@PathVariable("name") String name,@RequestParam(value="tag",required=false,max=4) List<String> tags);
 @GetMapping("/header") Echo header(@RequestHeader("X-Mode") String mode);
 @PostMapping("/write") HttpResult<Write> write(@RequestBody Write body);
 @GetMapping("/redirect") HttpResult<Write> redirect();
 @GetMapping("/external") HttpResult<Write> external();
}''',
        "Web": '''@RestController public class Web {
 private final Peer peer;
 public Web(Peer peer) { this.peer=peer; }
 @GetMapping("/read/{name}") @GatewayRoute(permission="consumer:read")
 public Echo read(@PathVariable("name") String name,@RequestParam(value="tag",required=false,max=4) List<String> tags) { return peer.echo(name,tags); }
 @GetMapping("/header") public Echo header() { return peer.header("fixed-mode"); }
 @PostMapping("/write") public HttpResult<Write> write(@RequestBody Write body) { return peer.write(body); }
 @GetMapping("/redirect") public HttpResult<Write> redirect() { return peer.redirect(); }
 @GetMapping("/external") public HttpResult<Write> external() { return peer.external(); }
}'''})
    base_cp = [jar("framework", "hoori-micro"), *runtime_classpath(runtime, receipt)]
    evidence = {"runtime": receipt["source"], "source_removed_before_run": True,
                "artifacts": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (provider, consumer)}, "engines": {}}
    for engine in ("interpreter", "mixed"):
        ports, processes, logs = {}, {}, {}
        for role in ("registry", "provider", "consumer", "gateway"):
            with socket.socket() as free:
                free.bind(("127.0.0.1", 0))
                ports[role] = free.getsockname()[1]
        forwarded = []

        class Proxy(BaseHTTPRequestHandler):
            def forward(self):
                connection = HTTPConnection("127.0.0.1", ports["provider"], timeout=15)
                try:
                    body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                    connection.request(self.command, self.path, body, dict(self.headers))
                    reply = connection.getresponse()
                    data = reply.read()
                    forwarded.append((self.command, self.path))
                    if self.command == "POST" and self.path == "/write":
                        self.close_connection = True  # Native provider committed; deliberately lose its response.
                        return
                    self.send_response(reply.status)
                    for name, value in reply.getheaders():
                        if name.lower() not in ("content-length", "connection", "transfer-encoding", "set-cookie", "authorization"):
                            self.send_header(name, value)
                    self.send_header("Content-Length", str(len(data)))
                    self.send_header("Set-Cookie", "PRIVATE=secret")
                    self.send_header("Authorization", "PRIVATE")
                    self.end_headers()
                    self.wfile.write(data)
                finally:
                    connection.close()
            do_GET = do_POST = forward
            def log_message(self, *args):
                pass

        def request(role, method, path, body=None):
            connection = HTTPConnection("127.0.0.1", ports[role], timeout=20)
            try:
                connection.request(method, path, None if body is None else json.dumps(body).encode(), {"Content-Type": "application/json"})
                reply = connection.getresponse()
                return reply.status, {k.lower(): v for k, v in reply.getheaders()}, reply.read()
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
                except OSError:
                    pass
                time.sleep(.025)
            raise AssertionError("Independent native apps did not become ready")

        with ThreadingHTTPServer(("127.0.0.1", 0), Proxy) as proxy:
            server = threading.Thread(target=proxy.serve_forever)
            server.start()
            try:
                for role in ports:
                    main_class = {"registry": "hoori/micro/Registry", "provider": "provider/Application",
                                  "consumer": "consumer/Application", "gateway": "hoori/micro/Gateway"}[role]
                    cp = [*base_cp, *([provider] if role == "provider" else [consumer] if role == "consumer" else [])]
                    env = os.environ | {"PATH": "/nonexistent", "JAVA_HOME": "/nonexistent",
                        "HOORI_BIND_ADDRESS": "127.0.0.1", "HOORI_PORT": str(ports[role]), "HOORI_INSTANCE_ID": role,
                        "HOORI_REGISTRY_URL": f"http://127.0.0.1:{ports['registry']}",
                        "HOORI_ADVERTISE_URL": f"http://127.0.0.1:{proxy.server_port if role == 'provider' else ports[role]}",
                        "HOORI_HEARTBEAT_MS": "250", "HOORI_REGISTRY_TTL_MS": "6000", "HOORI_CLIENT_TIMEOUT_MS": "5000",
                        "HOORI_WORK_TIMEOUT_MS": "8000", "HOORI_REQUEST_TIMEOUT_MS": "10000", "HOORI_SHUTDOWN_GRACE_MS": "1000",
                        "HOORI_GATEWAY_PERMISSIONS": "peer:read,peer:write,consumer:read"}
                    log = (work / f"{engine}-{role}.log").open("w")
                    logs[role] = log
                    processes[role] = subprocess.Popen([str(runtime / "bin/hoori"), "run", "--engine", engine, "--live-output",
                        "--graceful-signals", "--max-heap-bytes", "33554432", "--allow-environment-read", "--allow-resource-read",
                        "--allow-network-listen", "--allow-network-connect", "--class-path", ":".join(map(str, cp)), main_class],
                        env=env, stdout=log, stderr=log)
                    until(lambda: request(role, "GET", "/health/ready")[0] == 200)
                until(lambda: request("consumer", "GET", "/read/ready")[0] == 200)
                until(lambda: request("gateway", "GET", "/read/ready")[0] == 200)
                suffix = "/M%C3%B6hren%2B100%2525?tag=first%2B&tag=%C3%84pfel&tag="
                expected = {"name": "Möhren+100%25", "tags": ["first+", "Äpfel", ""], "header": ""}
                for role, path in (("provider", "/echo"), ("consumer", "/read"), ("gateway", "/echo"), ("gateway", "/read")):
                    status, headers, body = request(role, "GET", path + suffix)
                    assert status == 200 and json.loads(body) == expected, (role, status, body)
                    assert "set-cookie" not in headers and "authorization" not in headers
                assert json.loads(request("consumer", "GET", "/header")[2])["header"] == "fixed-mode"
                for role in ("consumer", "gateway"):
                    status, headers, body = request(role, "GET", "/external")
                    assert status == 200 and json.loads(body) == {"title": "ok"} and "location" not in headers, (role, status, headers, body)
                    before = len(forwarded)
                    assert request(role, "GET", "/redirect")[0] == 502
                    assert forwarded[before:] == [("GET", "/redirect")], forwarded[before:]
                for count, role in enumerate(("consumer", "gateway"), 1):
                    before = len(forwarded)
                    status, _, body = request(role, "POST", "/write", {"title": "once"})
                    assert status in (502, 504) and b"PRIVATE" not in body, (status, body)
                    assert json.loads(request("provider", "GET", "/writes")[2]) == count
                    assert forwarded[before:] == [("POST", "/write")], forwarded[before:]
                    assert request("consumer", "GET", "/read/recovered")[0] == 200
                evidence["engines"][engine] = {"checks": ["independent source-free provider and consumer",
                    "UTF-8 path and percent decoded once; repeated query order and empty value", "explicit client header",
                    "credential headers and private Location filtered", "no redirect follow", "lost POST response: one write, no replay, pool recovery"]}
                print("PASS:", engine, "independent clients, encoding, response metadata and no write replay", flush=True)
            finally:
                for role, process in reversed(list(processes.items())):
                    if process.poll() is None:
                        process.send_signal(signal.SIGTERM)
                        try:
                            process.wait(timeout=15)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait(timeout=5)
                    assert process.returncode == 0, Path(logs[role].name).read_text()
                proxy.shutdown()
                server.join()
                for log in logs.values():
                    log.close()
    (ROOT / ".cache/mvc-independent-clients.json").write_text(json.dumps(evidence, indent=2) + "\n")


if __name__ == "__main__":
    main()
