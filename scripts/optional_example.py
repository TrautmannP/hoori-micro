#!/usr/bin/env python3
"""Build/run explicitly optional consumers, outside the HTTP reactor and runtime classpath."""
import argparse
import json
import os
from pathlib import Path
import subprocess

from runtime_check import ROOT, install, runtime_jars, verify
from stage import jar


def configuration(name):
    config = json.loads((ROOT / "examples" / name / "example.json").read_text())
    lock = json.loads((ROOT / "hoori.lock.json").read_text())
    lock["runtimeSdks"] += config["runtimeSdks"]
    lock["buildSdks"] = config["buildSdks"]
    return config, lock


def classpath(name, runtime, receipt, lock):
    return [jar("framework", "hoori-micro"), jar("examples/demo-contracts", "hoori-micro-demo-contracts"),
            jar("examples/" + name, name), *[runtime / p for p in runtime_jars(receipt, lock)],
            *sorted((ROOT / "examples" / name / "target/lib").glob("*.jar"))]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("build", "run"))
    parser.add_argument("example", choices=("task-facade",))
    parser.add_argument("--distribution", type=Path, default=ROOT / ".docker-context/runtime")
    args = parser.parse_args()
    runtime = args.distribution.resolve(strict=True)
    config, lock = configuration(args.example)
    receipt = verify(runtime, lock)
    if args.action == "build":
        repo = install(runtime, receipt, lock)
        mvn = ["mvn", "--batch-mode", "--no-transfer-progress", f"-Dmaven.repo.local={repo}"]
        subprocess.run(mvn + ["-pl", "framework,examples/demo-contracts", "-am", "install"], cwd=ROOT, check=True)
        module = ROOT / "examples" / args.example
        subprocess.run(mvn + ["-f", str(module / "pom.xml"), "clean", "verify",
            "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies",
            "-DincludeScope=runtime", "-DexcludeGroupIds=dev.hoori", f"-DoutputDirectory={module / 'target/lib'}"],
            cwd=ROOT, check=True)
        print("Built optional example:", args.example)
    else:
        env = os.environ.copy()
        env.setdefault("HOORI_BIND_ADDRESS", "127.0.0.1")
        env.setdefault("HOORI_PORT", "8084")
        env.setdefault("HOORI_REGISTRY_URL", "http://127.0.0.1:8090")
        command = [str(runtime / "bin/hoori"), "run", "--engine", env.get("HOORI_ENGINE", "mixed"),
            "--live-output", "--graceful-signals", "--max-heap-bytes", "33554432",
            "--allow-environment-read", "--allow-network-listen", "--allow-network-connect",
            "--class-path", ":".join(map(str, classpath(args.example, runtime, receipt, lock))), config["mainClass"]]
        os.execve(command[0], command, env)


if __name__ == "__main__":
    main()
