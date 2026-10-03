"""Receipt/hash tests use synthetic files, not a Hoori executable."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import runtime_check
import stage


class RuntimeCheckTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        self.lock = json.loads((runtime_check.ROOT / "hoori.lock.json").read_text())
        self.receipt = {"distribution": "headless", "source": {"revision": self.lock["revision"], "dirty": False},
                        "runtime": {"revision": self.lock["revision"], "features": []},
                        "guest_base": "lib/hoori-guest-base-0.4.0.jar", "sdk_artifacts": {}}
        for name in ("bin/hoori", "SYSTEM.txt", "GUEST-LICENSE.txt", "verify.sh",
                     "lib/hoori-guest-base-0.4.0.jar", "lib/unused-optional.jar"):
            target = self.path / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("test fixture, not a runtime\n")
        for name in self.lock["runtimeSdks"]:
            pom = (f'<project xmlns="{runtime_check.NS["m"]}"><modelVersion>4.0.0</modelVersion>'
                   f'<groupId>dev.hoori</groupId><artifactId>{name}</artifactId>'
                   '<version>0.1.0</version></project>')
            path = self.path / f"metadata/{name}.pom"
            path.parent.mkdir(exist_ok=True)
            path.write_text(pom)
            with zipfile.ZipFile(self.path / f"lib/{name}-0.1.0.jar", "w") as archive:
                archive.writestr(f"META-INF/maven/dev.hoori/{name}/pom.xml", pom)
            artifact = {"jar": f"lib/{name}-0.1.0.jar", "pom": f"metadata/{name}.pom"}
            if name == "hoori-concurrent-api":
                artifact["runtime_contract"] = {"runtime_revision": self.lock["revision"],
                    "guest_base": self.receipt["guest_base"], "requires_same_distribution": True}
            self.receipt["sdk_artifacts"][f"dev.hoori:{name}:0.1.0"] = artifact
        self.receipt_and_hashes()

    def hashes(self):
        lines = [hashlib.sha256(p.read_bytes()).hexdigest() + "  " + str(p.relative_to(self.path))
                 for p in sorted(self.path.rglob("*")) if p.is_file() and p.name != "SHA256SUMS"]
        (self.path / "SHA256SUMS").write_text("\n".join(lines) + "\n")

    def receipt_and_hashes(self):
        self.receipt["artifacts"] = {str(p.relative_to(self.path)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in self.path.rglob("*") if p.is_file() and p.name not in ("SHA256SUMS", "DISTRIBUTION.json")}
        (self.path / "DISTRIBUTION.json").write_text(json.dumps(self.receipt))
        self.hashes()

    def rejects(self):
        with self.assertRaises(ValueError):
            runtime_check.verify(self.path, self.lock)

    def test_valid(self):
        self.assertEqual(self.receipt, runtime_check.verify(self.path, self.lock))

    def test_required_pom_even_with_rehashed_manifest(self):
        (self.path / "metadata/hoori-concurrent-http-api.pom").unlink()
        self.receipt_and_hashes()
        self.rejects()

    def test_mixed_pom_even_with_rehashed_receipt(self):
        (self.path / "metadata/hoori-concurrent-http-api.pom").write_text("<project/>")
        self.receipt_and_hashes()
        self.rejects()

    def test_receipt_hashes_not_just_manifest(self):
        (self.path / "lib/hoori-concurrent-api-0.1.0.jar").write_bytes(b"foreign SDK")
        self.hashes()
        self.rejects()

    def test_concurrent_runtime_binding(self):
        self.receipt["sdk_artifacts"]["dev.hoori:hoori-concurrent-api:0.1.0"]["runtime_contract"]["runtime_revision"] = "other"
        self.receipt_and_hashes()
        self.rejects()

    def test_cache_isolated_by_artifacts(self):
        first = runtime_check.maven_repository(self.path)
        (self.path / "SYSTEM.txt").write_text("different build")
        self.receipt_and_hashes()
        self.assertNotEqual(first, runtime_check.maven_repository(self.path))

    def test_stage_preserves_receipt_and_selects_classpath(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for module, artifact in (("framework", "hoori-micro"),
                    ("examples/demo-contracts", "hoori-micro-demo-contracts"),
                    ("examples/recipes-service", "recipes-service"),
                    ("examples/pantry-service", "pantry-service"),
                    ("examples/shopping-service", "shopping-service")):
                target = root / module / "target"
                target.mkdir(parents=True)
                (target / (artifact + "-0.1.0.jar")).write_bytes(b"application fixture")
            (root / "docker").mkdir()
            (root / "docker/Dockerfile").write_text("FROM scratch\n")
            with patch.object(stage, "ROOT", root):
                stage.stage(self.path)
            output = root / ".docker-context"
            self.assertEqual(self.receipt, runtime_check.verify(output / "runtime", self.lock))
            jars = (output / "runtime-classpath.txt").read_text().splitlines()
            self.assertEqual(runtime_check.runtime_jars(self.receipt, self.lock), jars)
            self.assertEqual(5, len(jars))
            self.assertNotIn("lib/unused-optional.jar", jars)

    def test_modified(self):
        (self.path / "bin/hoori").write_text("changed")
        self.rejects()

    def test_missing(self):
        (self.path / "SYSTEM.txt").unlink()
        self.rejects()

    def test_unlisted(self):
        (self.path / "unexpected.jar").write_text("unexpected")
        self.rejects()

    def test_dirty(self):
        self.receipt["source"]["dirty"] = True
        self.receipt_and_hashes()
        self.rejects()

    def test_wrong_revision(self):
        self.receipt["source"]["revision"] = "wrong"
        self.receipt_and_hashes()
        self.rejects()

    def test_wrong_binary(self):
        self.receipt["runtime"]["revision"] = "wrong"
        self.receipt_and_hashes()
        self.rejects()

    def test_not_headless(self):
        self.receipt["runtime"]["features"] = ["desktop"]
        self.receipt_and_hashes()
        self.rejects()

    def test_symlink(self):
        (self.path / "link").symlink_to(self.path / "bin/hoori")
        self.rejects()

    def test_unsafe_manifest_path(self):
        (self.path / "SHA256SUMS").write_text("0" * 64 + "  ../outside\n")
        self.rejects()

    def test_duplicate_manifest_path(self):
        manifest = self.path / "SHA256SUMS"
        manifest.write_text(manifest.read_text() * 2)
        self.rejects()

    def test_nested_manifest(self):
        (self.path / "lib/SHA256SUMS").write_text("ignored?")
        self.rejects()
