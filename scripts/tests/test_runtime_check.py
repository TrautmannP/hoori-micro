"""Receipt/hash tests use synthetic files, not a Hoori executable."""
from __future__ import annotations
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "runtime_check.py"
spec = importlib.util.spec_from_file_location("runtime_check", SCRIPT)
runtime_check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime_check)


class RuntimeCheckTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        self.lock = json.loads((runtime_check.ROOT / "hoori.lock.json").read_text())
        self.receipt = {"distribution": "headless", "source": {"revision": self.lock["revision"], "dirty": False},
                        "runtime": {"revision": self.lock["revision"], "features": []}}
        for name in ("bin/hoori", "SYSTEM.txt", "GUEST-LICENSE.txt", "verify.sh",
                     "lib/hoori-guest-base-0.4.0.jar", "lib/hoori-http-api-0.1.0.jar", "lib/hoori-rest-api-0.1.0.jar"):
            target = self.path / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("test fixture, not a runtime\n")
        self.receipt_and_hashes()

    def hashes(self):
        lines = [hashlib.sha256(p.read_bytes()).hexdigest() + "  " + str(p.relative_to(self.path))
                 for p in sorted(self.path.rglob("*")) if p.is_file() and p.name != "SHA256SUMS"]
        (self.path / "SHA256SUMS").write_text("\n".join(lines) + "\n")

    def receipt_and_hashes(self):
        (self.path / "DISTRIBUTION.json").write_text(json.dumps(self.receipt))
        self.hashes()

    def rejects(self):
        with self.assertRaises(ValueError):
            runtime_check.verify(self.path, self.lock)

    def test_valid(self):
        self.assertEqual(self.receipt, runtime_check.verify(self.path, self.lock))

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
