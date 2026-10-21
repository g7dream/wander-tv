"""The SAM template parses and passes the structural check (skipped if PyYAML is not installed)."""

import os
import sys
import unittest

TOOLS = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "tools"))

try:
    import yaml  # noqa: F401

    HAVE_YAML = True
except ImportError:  # pragma: no cover
    HAVE_YAML = False


@unittest.skipUnless(HAVE_YAML, "PyYAML not installed")
class TemplateTests(unittest.TestCase):
    def setUp(self):
        if TOOLS not in sys.path:
            sys.path.insert(0, TOOLS)
        import check_template

        self.ct = check_template

    def test_template_is_deployable(self):
        self.assertEqual(self.ct.check(self.ct.load()), [])

    def test_checker_catches_a_dangling_ref(self):
        doc = self.ct.load()
        doc["Outputs"]["Broken"] = {"Value": self.ct.Tagged("Ref", "DoesNotExist")}
        self.assertTrue(any("DoesNotExist" in p for p in self.ct.check(doc)))

    def test_checker_catches_wildcard_iam(self):
        doc = self.ct.load()
        fn = doc["Resources"]["LumoFunction"]["Properties"]
        fn["Policies"][0]["Statement"].append({"Effect": "Allow", "Action": ["dynamodb:*"], "Resource": "*"})
        self.assertTrue(any("wildcard" in p for p in self.ct.check(doc)))


if __name__ == "__main__":
    unittest.main()
