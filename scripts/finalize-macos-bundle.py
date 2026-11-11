#!/usr/bin/env python3
"""Adapt JDK 17's positive app-version constraint without changing public SemVer.

Run after jpackage and BEFORE any Developer ID signing/notarization. Only ad-hoc
bundles may be rewritten; the resulting bundle receives a fresh ad-hoc signature.
"""
import pathlib
import plistlib
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[1]
properties = dict(line.strip().split("=", 1) for line in (root / "gradle.properties").read_text().splitlines()
                  if "=" in line and not line.lstrip().startswith("#"))
bundle = pathlib.Path(sys.argv[1])
identity = subprocess.run(["codesign", "-dv", "--verbose=2", str(bundle)], capture_output=True, text=True)
if "Authority=" in identity.stderr:
    raise SystemExit("Finalize metadata before Developer ID signing. Refusing to modify an identity-signed bundle.")

info = bundle / "Contents/Info.plist"
values = plistlib.loads(info.read_bytes())
semantic = properties["appVersion"]
values["CFBundleShortVersionString"] = semantic.split("-", 1)[0].split("+", 1)[0]
values["CFBundleVersion"] = properties["macBundleVersion"]
values["KasaneSemanticVersion"] = semantic
info.write_bytes(plistlib.dumps(values, sort_keys=False))
subprocess.run(["codesign", "--force", "--sign", "-", str(bundle)], check=True, capture_output=True)
subprocess.run(["codesign", "--verify", "--deep", "--strict", str(bundle)], check=True, capture_output=True)
print(f"Product {semantic}; macOS build {values['CFBundleVersion']}; ad-hoc bundle verified")
