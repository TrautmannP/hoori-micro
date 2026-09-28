#!/usr/bin/env python3
"""Verify a headless distribution before installing or copying its artifacts."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]


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
    required = ["bin/hoori", "SYSTEM.txt", "GUEST-LICENSE.txt", "verify.sh",
                f"lib/hoori-guest-base-{lock['guestVersion']}.jar",
                f"lib/hoori-http-api-{lock['sdkVersion']}.jar",
                f"lib/hoori-rest-api-{lock['sdkVersion']}.jar"]
    if not all(name in actual for name in required):
        raise ValueError("Distribution lacks required runtime/SDK/license files")
    return receipt


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: scripts/runtime_check.py /path/to/headless-distribution", file=sys.stderr)
        return 2
    try:
        receipt = verify(Path(sys.argv[1]))
        print("Verified Hoori distribution:", receipt["source"]["revision"])
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Runtime verification failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
