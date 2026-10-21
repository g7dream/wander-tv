"""The SAM template parses and passes the structural check (skipped if PyYAML is not installed)."""

import os
import re
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

    # ------------------------------------------------------------ guardrail strengths

    def _filters(self, doc):
        guard = doc["Resources"]["FamilyGuardrail"]["Properties"]
        return {f["Type"]: f for f in guard["ContentPolicyConfig"]["FiltersConfig"]}

    def test_both_filter_sides_follow_parameters_that_default_to_medium(self):
        doc = self.ct.load()
        params = doc["Parameters"]
        for kind in self.ct.FAMILY_FILTERS:
            f = self._filters(doc)[kind]
            self.assertEqual(("Ref", "FilterStrength"), (f["InputStrength"].tag, f["InputStrength"].value), kind)
            self.assertEqual(("Ref", "OutputFilterStrength"), (f["OutputStrength"].tag, f["OutputStrength"].value), kind)
        for name in ("FilterStrength", "OutputFilterStrength"):
            self.assertEqual("MEDIUM", params[name]["Default"], name)
            self.assertEqual({"HIGH", "MEDIUM", "LOW"}, set(params[name]["AllowedValues"]), name)
        pa = self._filters(doc)["PROMPT_ATTACK"]
        self.assertEqual(("HIGH", "NONE"), (pa["InputStrength"], pa["OutputStrength"]))

    def test_guardrail_version_is_recut_when_either_strength_changes(self):
        desc = self.ct.load()["Resources"]["FamilyGuardrailVersion"]["Properties"]["Description"].value
        self.assertIn("${FilterStrength}", desc)
        self.assertIn("${OutputFilterStrength}", desc)

    def test_checker_catches_a_hard_coded_or_disabled_strength(self):
        doc = self.ct.load()
        self._filters(doc)["VIOLENCE"]["OutputStrength"] = "NONE"
        self.assertTrue(any("VIOLENCE OutputStrength" in p for p in self.ct.check(doc)))
        doc = self.ct.load()
        doc["Parameters"]["OutputFilterStrength"]["AllowedValues"].append("NONE")
        self.assertTrue(any("OutputStrength" in p for p in self.ct.check(doc)))

    def test_checker_catches_a_version_that_ignores_a_strength(self):
        doc = self.ct.load()
        version = doc["Resources"]["FamilyGuardrailVersion"]["Properties"]
        version["Description"] = self.ct.Tagged("Sub", "Wander TV family filters, input strength ${FilterStrength}")
        self.assertTrue(any("OutputFilterStrength" in p for p in self.ct.check(doc)))

    def test_deploy_scripts_only_override_parameters_the_template_has(self):
        params = set(self.ct.load()["Parameters"])
        backend = os.path.dirname(TOOLS)
        for script, pattern, end in (("deploy.ps1", r'"(\w+)=\$\w+"', ")"), ("deploy.sh", r'"(\w+)=\$[A-Z_]+"', "; then")):
            with open(os.path.join(backend, script), encoding="utf-8") as f:
                text = f.read()
            segment = text.split("--parameter-overrides", 1)[1].split(end, 1)[0]
            names = set(re.findall(pattern, segment))
            self.assertIn("OutputFilterStrength", names, script)
            self.assertIn("FilterStrength", names, script)
            self.assertLessEqual(names, params, script)


if __name__ == "__main__":
    unittest.main()
