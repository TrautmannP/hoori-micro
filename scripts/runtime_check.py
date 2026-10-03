#!/usr/bin/env python3
"""Verify a headless distribution before installing or copying its artifacts."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def runtime_jars(receipt: dict, lock: dict | None = None) -> list[str]:
    lock = lock or json.loads((ROOT / "hoori.lock.json").read_text())
    return [receipt["guest_base"]] + [
        receipt["sdk_artifacts"][f"dev.hoori:{name}:{lock['sdkVersion']}"]["jar"]
        for name in lock["runtimeSdks"]]


def maven_repository(directory: Path) -> Path:
    identity = hashlib.sha256((directory / "SHA256SUMS").read_bytes()).hexdigest()
    return ROOT / ".cache/m2" / identity


def verify(directory: Path, lock: dict | None = None) -> dict:
    directory = directory.resolve(strict=True)
    lock = lock or json.loads((ROOT / "hoori.lock.json").read_text())
    expected = {}
    for line in (directory / "SHA256SUMS").read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if not match:
            raise ValueError("Malformed SHA256SUMS line")
        checksum, name = match.groups()
        path = Path(name)
        if path.is_absolute() or ".." in path.parts or name in expected or str(path) != name:
            raise ValueError("Unsafe or duplicate manifest path")
        expected[name] = checksum
    actual = {}
    for path in directory.rglob("*"):
        if path.is_symlink():
            raise ValueError("Symlink in runtime distribution")
        if path.is_file() and path.name != "SHA256SUMS":
            actual[str(path.relative_to(directory))] = hashlib.sha256(path.read_bytes()).hexdigest()
    # Only the root SHA256SUMS is exempt; a nested file with that name is unlisted.
    for path in directory.rglob("SHA256SUMS"):
        if path != directory / "SHA256SUMS":
            raise ValueError("Unexpected nested manifest")
    if actual != expected:
        raise ValueError("Runtime has modified, missing or unlisted files")
    receipt = json.loads((directory / "DISTRIBUTION.json").read_text())
    if receipt.get("distribution") != "headless" or receipt.get("source", {}).get("dirty") is not False:
        raise ValueError("Expected a clean headless Hoori distribution")
    if receipt.get("source", {}).get("revision") != lock["revision"]:
        raise ValueError("Hoori revision does not match hoori.lock.json; qualify and update the lock explicitly")
    if receipt.get("runtime", {}).get("revision") != lock["revision"]:
        raise ValueError("Binary and source revision differ")
    if receipt.get("runtime", {}).get("features") != []:
        raise ValueError("Runtime is not a headless build")
    artifacts = {name: digest for name, digest in actual.items() if name != "DISTRIBUTION.json"}
    if receipt.get("artifacts") != artifacts:
        raise ValueError("Distribution receipt and artifact checksums differ")
    guest = f"lib/hoori-guest-base-{lock['guestVersion']}.jar"
    if receipt.get("guest_base") != guest:
        raise ValueError("Unexpected Guest Base artifact")
    required = ["bin/hoori", "SYSTEM.txt", "GUEST-LICENSE.txt", "verify.sh", guest]
    selected = {f"dev.hoori:{name}:{lock['sdkVersion']}" for name in lock["runtimeSdks"]}
    for coordinate in selected:
        artifact = receipt.get("sdk_artifacts", {}).get(coordinate)
        if not artifact:
            raise ValueError(f"Missing SDK metadata: {coordinate}")
        group, name, version = coordinate.split(":")
        jar, pom = f"lib/{name}-{version}.jar", f"metadata/{name}.pom"
        if artifact.get("jar") != jar or artifact.get("pom") != pom or jar not in actual or pom not in actual:
            raise ValueError(f"Missing or inconsistent SDK/POM: {coordinate}")
        original = (directory / pom).read_bytes()
        with zipfile.ZipFile(directory / jar) as archive:
            if archive.read(f"META-INF/maven/{group}/{name}/pom.xml") != original:
                raise ValueError(f"SDK and original POM differ: {coordinate}")
        model = ET.fromstring(original)
        if [model.findtext("m:" + key, namespaces=NS) for key in ("groupId", "artifactId", "version")] != [group, name, version]:
            raise ValueError(f"Unexpected Maven coordinates: {coordinate}")
        for dependency in model.findall("m:dependencies/m:dependency", NS):
            if dependency.findtext("m:scope", "compile", NS) in ("provided", "test"):
                continue
            dependency_id = ":".join(dependency.findtext("m:" + key, "", NS)
                                     for key in ("groupId", "artifactId", "version"))
            if dependency_id not in selected:
                raise ValueError(f"Required dependency missing from runtimeSdks: {dependency_id}")
    concurrent = receipt["sdk_artifacts"][f"dev.hoori:hoori-concurrent-api:{lock['sdkVersion']}"]
    contract = concurrent.get("runtime_contract", {})
    if (contract.get("runtime_revision") != lock["revision"] or contract.get("guest_base") != guest
            or contract.get("requires_same_distribution") is not True):
        raise ValueError("Concurrent SDK runtime contract differs from the pinned distribution")
    if not all(name in actual for name in required):
        raise ValueError("Distribution lacks required runtime/SDK/license files")
    return receipt


def install(directory: Path, receipt: dict) -> Path:
    repo = maven_repository(directory)
    repo.mkdir(parents=True, exist_ok=True)
    command = ["mvn", "--batch-mode", "--no-transfer-progress", "-q", f"-Dmaven.repo.local={repo}",
               "org.apache.maven.plugins:maven-install-plugin:3.1.3:install-file"]
    lock = json.loads((ROOT / "hoori.lock.json").read_text())
    # Guest Base has no published POM; use the upstream distribution's installation convention.
    subprocess.run(command + [f"-Dfile={directory / receipt['guest_base']}", "-DgroupId=dev.hoori",
                   "-DartifactId=hoori-guest-base", f"-Dversion={lock['guestVersion']}",
                   "-Dpackaging=jar", "-DgeneratePom=true"], check=True, stdout=sys.stderr)
    for name in lock["runtimeSdks"]:
        artifact = receipt["sdk_artifacts"][f"dev.hoori:{name}:{lock['sdkVersion']}"]
        subprocess.run(command + [f"-Dfile={directory / artifact['jar']}",
                       f"-DpomFile={directory / artifact['pom']}", "-DgeneratePom=false"],
                       check=True, stdout=sys.stderr)
    return repo


def main() -> int:
    if len(sys.argv) not in (2, 3) or (len(sys.argv) == 3 and sys.argv[2] not in ("--classpath", "--install")):
        print("usage: scripts/runtime_check.py /path/to/headless-distribution [--classpath|--install]", file=sys.stderr)
        return 2
    try:
        directory = Path(sys.argv[1]).resolve(strict=True)
        receipt = verify(directory)
        if sys.argv[-1] == "--classpath":
            print(":".join(str(directory / jar) for jar in runtime_jars(receipt)))
        elif sys.argv[-1] == "--install":
            print(install(directory, receipt))
        else:
            print("Verified Hoori distribution:", receipt["source"]["revision"])
        return 0
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, ET.ParseError, subprocess.CalledProcessError) as error:
        print(f"Runtime verification failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
