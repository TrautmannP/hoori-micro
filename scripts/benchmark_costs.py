#!/usr/bin/env python3
"""Attribute framework time/allocation costs on the real pinned guest; not an HTTP benchmark."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import subprocess
import tempfile

from runtime_check import ROOT, runtime_classpath, verify


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--probe", choices=("framework", "deadlines"), default="framework")
    parser.add_argument("--framework", type=Path, default=ROOT / "framework/target/hoori-micro-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repeats", type=int, default=3)
    args = parser.parse_args()
    if args.repeats < 1 or args.output.exists():
        parser.error("Use positive repeats and a new output file")
    runtime = ROOT / ".docker-context/runtime"
    receipt = verify(runtime)
    if receipt["runtime"]["profile"] != "release":
        parser.error("Costs require the pinned release distribution")
    deadlines = args.probe == "deadlines"
    class_name = "DeadlineCosts" if deadlines else "FrameworkCosts"
    source = ROOT / f"framework/src/test/java/hoori/micro/{class_name}.java"
    classpath = ([] if deadlines else [args.framework.resolve()]) + runtime_classpath(runtime, receipt)
    document = {"probe": args.probe, "runtime_receipt": receipt, "commit": subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "framework_sha256": None if deadlines else hashlib.sha256(args.framework.read_bytes()).hexdigest(),
        "probe_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "host": dict(zip(("system", "node", "release", "version", "machine"), os.uname())), "runs": []}
    order = [(engine, repeat, mode) for repeat in range(args.repeats)
             for engine in ("interpreter", "mixed")
             for mode in (("timed", "unlimited") if deadlines else (None,))]
    random.Random(11).shuffle(order)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="hoori-micro-costs-") as directory:
        cp = ":".join(map(str, classpath))
        subprocess.run(["javac", "--release", "21", "-proc:none", "-cp", cp, "-d", directory, str(source)], check=True)
        for engine, repeat, mode in order:
            result = subprocess.run([str(runtime / "bin/hoori"), "run", "--engine", engine,
                "--class-path", directory + ":" + cp, "hoori/micro/" + class_name,
                *(["--arg", mode] if mode else [])],
                capture_output=True, text=True, timeout=120, check=True)
            samples = [json.loads(line) for line in result.stdout.splitlines()]
            assert len(samples) == (5 if deadlines else 15), result.stdout
            assert all(sample["calls"] == (1000 if deadlines else 10000) for sample in samples), result.stdout
            document["runs"].append({"engine": engine, "repeat": repeat, "mode": mode, "samples": samples})
            args.output.write_text(json.dumps(document, indent=2) + "\n")
            print(engine, repeat, mode or "framework", "complete", flush=True)
    document["complete"] = True
    args.output.write_text(json.dumps(document, indent=2) + "\n")


if __name__ == "__main__":
    main()
