"""Synthetic JUnit reports exercise the validator without Gradle or Android."""
from importlib.util import spec_from_file_location, module_from_spec
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = spec_from_file_location("storage_regression", Path(__file__).with_name("check-storage-regression.py"))
module = module_from_spec(spec)
spec.loader.exec_module(module)


class ValidatorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.expected = {"repro": "S1_EXAMPLE"}

    def report(self, failure=None, *, kind="java.lang.AssertionError", skipped=False,
               classname="example.Test", method="repro"):
        suite = ET.Element("testsuite")
        case = ET.SubElement(suite, "testcase", classname=classname, name=method)
        if failure is not None:
            ET.SubElement(case, "failure", type=kind, message=failure)
        if skipped:
            ET.SubElement(case, "skipped")
        ET.ElementTree(suite).write(self.root / "TEST-example.xml", encoding="utf-8")

    def validate(self, original, status):
        module.validate_results(self.root, "example.Test", self.expected, original, status, "fixture")

    def test_valid_original_assertion(self):
        self.report("S1_EXAMPLE: wrong result", kind="org.junit.ComparisonFailure")
        self.validate(True, 1)

    def test_valid_repaired_result(self):
        self.report()
        self.validate(False, 0)

    def test_missing_report(self):
        with self.assertRaises(AssertionError):
            self.validate(True, 1)

    def test_wrong_assertion(self):
        self.report("unrelated failure")
        with self.assertRaises(AssertionError):
            self.validate(True, 1)

    def test_wrong_exception_even_with_marker(self):
        self.report("S1_EXAMPLE", kind="java.lang.IllegalStateException")
        with self.assertRaises(AssertionError):
            self.validate(True, 1)

    def test_skipped(self):
        self.report(skipped=True)
        with self.assertRaises(AssertionError):
            self.validate(False, 0)

    def test_original_that_passes_is_rejected(self):
        self.report()
        with self.assertRaises(AssertionError):
            self.validate(True, 0)

    def test_repaired_that_fails_is_rejected(self):
        self.report("S1_EXAMPLE")
        with self.assertRaises(AssertionError):
            self.validate(False, 1)

    def test_original_failure_but_gradle_passes(self):
        self.report("S1_EXAMPLE")
        with self.assertRaises(AssertionError):
            self.validate(True, 0)

    def test_passing_report_but_gradle_fails(self):
        self.report()
        with self.assertRaises(AssertionError):
            self.validate(False, 1)

    def test_wrong_test(self):
        self.report("S1_EXAMPLE", method="different")
        with self.assertRaises(AssertionError):
            self.validate(True, 1)


if __name__ == "__main__":
    unittest.main()
