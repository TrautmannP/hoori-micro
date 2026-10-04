#!/usr/bin/env python3
"""Prepare a tiny build context: verified runtime + built application JARs, no checkout/tools."""
from __future__ import annotations
from pathlib import Path
import shutil
import sys
from runtime_check import ROOT, external_jars, runtime_jars, verify


def jar(module: str, artifact: str) -> Path:
    matches = [p for p in (ROOT / module / "target").glob(artifact + "-*.jar")
               if not p.name.endswith(("-sources.jar", "-javadoc.jar", "-tests.jar"))]
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one built {artifact} JAR; run scripts/build.sh")
    return matches[0]


def stage(runtime: Path) -> None:
    receipt = verify(runtime)
    work = ROOT / ".docker-context.tmp"
    output = ROOT / ".docker-context"
    if work.exists():
        shutil.rmtree(work)
    try:
        shutil.copytree(runtime, work / "runtime")
        verify(work / "runtime")
        dependencies = external_jars(ROOT / "starter/target/lib")
        (work / "dependencies").mkdir()
        for dependency in dependencies:
            shutil.copy2(dependency, work / "dependencies" / dependency.name)
        (work / "runtime-classpath.txt").write_text("\n".join(runtime_jars(receipt)
            + ["../dependencies/" + p.name for p in dependencies]) + "\n")
        framework = jar("framework", "hoori-micro")
        contracts = jar("examples/demo-contracts", "hoori-micro-demo-contracts")
        apps = {"registry": [framework], "gateway": [framework]}
        for service in ("recipes", "pantry", "shopping"):
            apps[service] = [framework, contracts, jar(f"examples/{service}-service", f"{service}-service")]
        for app, jars in apps.items():
            target = work / "apps" / app / "lib"
            target.mkdir(parents=True)
            for source in jars:
                shutil.copy2(source, target / source.name)
        shutil.copytree(ROOT / "docker", work / "docker")
        shutil.copy2(ROOT / "docker/Dockerfile", work / "Dockerfile")
        if output.exists():
            shutil.rmtree(output)
        work.rename(output)
    finally:
        if work.exists():
            shutil.rmtree(work)
    print("Staged verified runtime and applications in .docker-context/")


if __name__ == "__main__":
    try:
        if len(sys.argv) != 2:
            raise ValueError("usage: scripts/stage.py /path/to/headless-distribution")
        stage(Path(sys.argv[1]).resolve(strict=True))
    except (OSError, ValueError, KeyError) as error:
        print(f"Staging failed: {error}", file=sys.stderr)
        raise SystemExit(1)
