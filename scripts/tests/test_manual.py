"""The manual's reference pages must name every config variable, metric and fixed route of the framework."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / "framework/src/main/java"
REFERENCE = ROOT / "pages/content/docs/reference"


def literals(pattern):
    found = set()
    for source in SOURCES.rglob("*.java"):
        found.update(re.findall(pattern, source.read_text(encoding="utf-8")))
    return found


def routes():
    # Matches the reference table cells, e.g. `GET` | `/health/live`.
    return {f"`{method}` | `{path}" for method, path in literals(r'controlRoute\(\s*"([A-Z]+)",\s*"([^"]+)"')}


class ManualReferenceTest(unittest.TestCase):
    def assert_documented(self, names, page):
        text = (REFERENCE / page).read_text(encoding="utf-8")
        self.assertTrue(names, "pattern matched nothing; update this test")
        self.assertEqual([], sorted(name for name in names if name not in text), page)

    def test_configuration_variables(self):
        self.assert_documented(literals(r'"(HOORI_[A-Z0-9_]+)"'), "configuration.mdx")

    def test_metric_names(self):
        self.assert_documented(literals(r'"(hoori_micro_[a-z_]+)'), "metrics.mdx")

    def test_fixed_routes(self):
        self.assert_documented(routes(), "endpoints.mdx")


if __name__ == "__main__":
    unittest.main()
