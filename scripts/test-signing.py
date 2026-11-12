#!/usr/bin/env python3
"""Safety checks for the identity-preserving packaging boundary; no keys needed."""
import importlib.util
import pathlib
import unittest
import json
import subprocess
import tempfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("finalizer", pathlib.Path(__file__).with_name("finalize-macos-bundle.py"))
finalizer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(finalizer)
notary_spec = importlib.util.spec_from_file_location("notarizer", pathlib.Path(__file__).with_name("notarize-local-dmg.py"))
notarizer = importlib.util.module_from_spec(notary_spec)
notary_spec.loader.exec_module(notarizer)
BUNDLE = pathlib.Path("/tmp/kasane-test/build/App.app")
NAME = "Example Developer (ABCDEFGHIJ)"
SIGNED = f"Authority=Developer ID Application: {NAME}\nAuthority=Developer ID Certification Authority\nTeamIdentifier=ABCDEFGHIJ"


class SigningBoundaryTest(unittest.TestCase):
    def test_unsigned_build_remains_adhoc(self):
        self.assertEqual(finalizer.final_signature_command(BUNDLE, BUNDLE, "Signature=adhoc")[-2:], ["-", str(BUNDLE)])

    def test_intel_unsigned_development_image_can_receive_its_initial_signature(self):
        self.assertFalse(finalizer.verify_original_signature(1, "App.app: code object is not signed at all"))
        self.assertEqual(finalizer.final_signature_command(BUNDLE, BUNDLE, "code object is not signed at all")[-2:], ["-", str(BUNDLE)])

    def test_inspection_errors_and_missing_identity_signatures_fail_closed(self):
        for message, identity in [("No such file or directory", None), ("invalid signature", None), ("code object is not signed at all", NAME)]:
            with self.subTest(message=message, identity=identity), self.assertRaises(ValueError):
                finalizer.verify_original_signature(1, message, identity)

    def test_identity_is_never_silently_downgraded(self):
        with self.assertRaises(ValueError):
            finalizer.final_signature_command(BUNDLE, BUNDLE, SIGNED)

    def test_matching_signer_preserves_entitlements_and_hardened_runtime(self):
        command = finalizer.final_signature_command(BUNDLE, BUNDLE, SIGNED, NAME)
        self.assertIn("--preserve-metadata=entitlements,requirements", command)
        self.assertIn("--timestamp", command)
        self.assertEqual(command[command.index("--options") + 1], "runtime")
        self.assertEqual(command[command.index("--sign") + 1], f"Developer ID Application: {NAME}")

    def test_other_identity_or_team_is_rejected(self):
        for info in [SIGNED.replace("Example Developer", "Someone Else"), SIGNED.replace("TeamIdentifier=ABCDEFGHIJ", "TeamIdentifier=0123456789")]:
            with self.subTest(info=info), self.assertRaises(ValueError):
                finalizer.final_signature_command(BUNDLE, BUNDLE, info, NAME)

    def test_requested_signing_cannot_fall_back_to_adhoc(self):
        with self.assertRaises(ValueError):
            finalizer.final_signature_command(BUNDLE, BUNDLE, "Signature=adhoc", NAME)

    def test_external_bundle_is_never_modified(self):
        for identity in [None, NAME]:
            with self.subTest(identity=identity), self.assertRaises(ValueError):
                finalizer.final_signature_command(pathlib.Path("/tmp/deliveries/App.app"), BUNDLE, SIGNED, identity)


class NotarizationGateTest(unittest.TestCase):
    def setUp(self):
        quiet = patch("builtins.print")
        quiet.start()
        self.addCleanup(quiet.stop)

    def test_rejected_or_pending_submission_is_not_success(self):
        for status, code in [("Invalid", 0), ("In Progress", 75), ("Accepted", 1)]:
            response = {"id": "2efe2717-52ef-43a5-96dc-0797e4ca1041", "status": status}
            with self.subTest(status=status), tempfile.TemporaryDirectory() as folder:
                working = pathlib.Path(folder)
                with patch.object(notarizer.subprocess, "run", return_value=subprocess.CompletedProcess([], code, json.dumps(response), "")), \
                     patch.object(notarizer, "run", return_value=subprocess.CompletedProcess([], 0, '{"issues":[]}', "")), \
                     self.assertRaises(SystemExit):
                    notarizer.submit(working / "app.zip", "test-profile", working, "app")
                self.assertEqual(json.loads((working / "app-submission.json").read_text())["id"], response["id"])

    def test_accepted_requires_a_submission_id(self):
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(notarizer.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, '{"status":"Accepted"}', "")), self.assertRaises(SystemExit):
                notarizer.submit(pathlib.Path(folder) / "app.zip", "test-profile", pathlib.Path(folder), "app")

    def test_accepted_submission_has_a_saved_reviewable_log(self):
        response = {"id": "2efe2717-52ef-43a5-96dc-0797e4ca1041", "status": "Accepted"}
        with tempfile.TemporaryDirectory() as folder:
            working = pathlib.Path(folder)
            with patch.object(notarizer.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, json.dumps(response), "")), \
                 patch.object(notarizer, "run", return_value=subprocess.CompletedProcess([], 0, '{"issues":[]}', "")):
                notarizer.submit(working / "app.zip", "test-profile", working, "app")
            self.assertEqual(json.loads((working / "app-notary-log.json").read_text()), {"issues": []})


guard_spec = importlib.util.spec_from_file_location("bundle_guard", pathlib.Path(__file__).with_name("check-bundle-not-running.py"))
bundle_guard = importlib.util.module_from_spec(guard_spec)
guard_spec.loader.exec_module(bundle_guard)


class LiveBundleGuardTest(unittest.TestCase):
    def test_first_build_needs_no_process_scan(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(bundle_guard.subprocess, "run") as scan:
            bundle_guard.check_bundle(pathlib.Path(folder) / "App.app")
            scan.assert_not_called()

    def test_only_a_confirmed_unused_executable_can_be_replaced(self):
        with tempfile.TemporaryDirectory() as folder:
            bundle = pathlib.Path(folder) / "App.app"
            executable = bundle / "Contents/MacOS/App"
            executable.parent.mkdir(parents=True)
            executable.touch()
            for code, output, error, allowed in [(1, "", "", True), (0, "12345", "", False), (1, "", "inspection denied", False)]:
                with self.subTest(code=code, output=output), patch.object(bundle_guard.subprocess, "run", return_value=subprocess.CompletedProcess([], code, output, error)):
                    if allowed:
                        bundle_guard.check_bundle(bundle)
                    else:
                        with self.assertRaises(SystemExit):
                            bundle_guard.check_bundle(bundle)


if __name__ == "__main__":
    unittest.main()
