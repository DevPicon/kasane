#!/usr/bin/env python3
import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location("version_check", pathlib.Path(__file__).with_name("check-version.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class VersionContractTest(unittest.TestCase):
    def test_accepts_initial_development_prereleases_and_metadata(self):
        for version in ["0.1.0", "0.1.0-alpha.1", "0.2.0-beta.2+build.3", "1.0.0-rc.1", "1.0.0"]:
            self.assertTrue(module.validate(version, "2", "v" + version), version)

    def test_rejects_leading_zeros_invalid_prereleases_and_mismatching_tags(self):
        for version in ["01.0.0", "0.1", "0.1.0-alpha.01", "0.1.0-", "0.1.0+", "0.1.0.1"]:
            self.assertFalse(module.validate(version, "2"), version)
        self.assertFalse(module.validate("0.1.0-alpha.1", "0"))
        self.assertFalse(module.validate("0.1.0-alpha.1", "2", "v1.0.0"))


if __name__ == "__main__":
    unittest.main()
