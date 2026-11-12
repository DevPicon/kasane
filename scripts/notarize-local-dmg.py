#!/usr/bin/env python3
"""Notarize a local Developer ID build using credentials already in Keychain.

Only prints public submission IDs/status. Keys/passwords are never arguments.
Final distribution stays in build/notarization; no uploads to GitHub are made.
"""
import argparse
import hashlib
import json
import os
import pathlib
import plistlib
import shutil
import subprocess
import sys
import tempfile
import uuid


def run(arguments, **kwargs):
    return subprocess.run(arguments, check=True, **kwargs)


def submit(artifact, profile, working, label):
    result = subprocess.run(["xcrun", "notarytool", "submit", str(artifact), "--keychain-profile", profile,
                             "--wait", "--timeout", "30m", "--output-format", "json"], capture_output=True, text=True)
    try:
        response = json.loads(result.stdout)
    except json.JSONDecodeError:
        raise SystemExit("Notarization did not return a submission result. Check the Keychain profile and network; no artifact was released.")
    (working / f"{label}-submission.json").write_text(json.dumps(response, indent=2))
    submission_id = response.get("id")
    try:
        uuid.UUID(submission_id)
    except (ValueError, TypeError, AttributeError):
        raise SystemExit("Notarization did not provide a valid submission ID; no artifact was released.")
    print(f"{label}: submission {submission_id}, status {response.get('status')}", flush=True)
    if submission_id:
        log = run(["xcrun", "notarytool", "log", submission_id, "--keychain-profile", profile], capture_output=True, text=True)
        (working / f"{label}-notary-log.json").write_text(log.stdout)
        try:
            issues = json.loads(log.stdout).get("issues") or []
            if issues:
                print(f"{label}: review {len(issues)} notarization issue(s) in the saved log.", flush=True)
        except json.JSONDecodeError:
            raise SystemExit("Notarization log was not valid JSON; inspect it before distribution.")
    if result.returncode != 0 or response.get("status") != "Accepted":
        raise SystemExit(f"{label}: notarization was not Accepted. Submission details remain at {working}; no final artifact was released.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keychain-profile", required=True)
    args = parser.parse_args()
    identity = os.environ.get("KASANE_SIGNING_IDENTITY", "").strip()
    if not identity:
        raise SystemExit("Set KASANE_SIGNING_IDENTITY to the public Developer ID name; build the signed app first.")
    full_identity = identity if identity.startswith("Developer ID Application: ") else f"Developer ID Application: {identity}"
    root = pathlib.Path(__file__).resolve().parents[1]
    properties = dict(line.strip().split("=", 1) for line in (root / "gradle.properties").read_text().splitlines()
                      if "=" in line and not line.lstrip().startswith("#"))
    source = root / "build/compose/binaries/main/app" / f"{properties['appDisplayName']}.app"
    info = run(["codesign", "-dv", "--verbose=4", str(source)], capture_output=True, text=True).stderr
    if f"Authority={full_identity}" not in info.splitlines() or "(runtime)" not in info or "Timestamp=" not in info:
        raise SystemExit("A matching Developer ID signature with hardened runtime and secure timestamp is required.")
    metadata = plistlib.loads((source / "Contents/Info.plist").read_bytes())
    if metadata.get("KasaneSemanticVersion") != properties["appVersion"] or metadata.get("CFBundleVersion") != properties["macBundleVersion"]:
        raise SystemExit("Build metadata does not match gradle.properties; rebuild before notarizing.")
    run(["codesign", "--verify", "--deep", "--strict", str(source)], capture_output=True)
    # Validate the existing credentials before uploading the app or creating a working copy.
    run(["xcrun", "notarytool", "history", "--keychain-profile", args.keychain_profile, "--output-format", "json"],
        stdout=subprocess.DEVNULL)
    parent = root / "build/notarization"
    parent.mkdir(parents=True, exist_ok=True)
    working = pathlib.Path(tempfile.mkdtemp(prefix=f"{properties['appVersion']}-", dir=parent))
    app = working / source.name
    shutil.copytree(source, app, symlinks=True)
    archive = working / "app-for-notarization.zip"
    run(["ditto", "-c", "-k", "--keepParent", str(app), str(archive)])
    print(f"Working directory: {working}", flush=True)
    submit(archive, args.keychain_profile, working, "app")
    run(["xcrun", "stapler", "staple", str(app)])
    run(["xcrun", "stapler", "validate", str(app)])
    run(["codesign", "--verify", "--deep", "--strict", str(app)], capture_output=True)
    run(["spctl", "--assess", "--type", "execute", "--verbose=2", str(app)])
    run([sys.executable, str(root / "scripts/package-test-dmg.py"), "--bundle", str(app),
         "--output-dir", str(working), "--signing-identity", identity])
    dmg = next(working.glob("*-signed.dmg"))
    submit(dmg, args.keychain_profile, working, "dmg")
    run(["xcrun", "stapler", "staple", str(dmg)])
    run(["xcrun", "stapler", "validate", str(dmg)])
    run(["codesign", "--verify", "--strict", str(dmg)], capture_output=True)
    run(["hdiutil", "verify", str(dmg)])
    run(["spctl", "--assess", "--type", "open", "--context", "context:primary-signature", "--verbose=2", str(dmg)])
    old_checksum = dmg.with_suffix(".dmg.sha256")
    final = dmg.with_name(dmg.name.replace("-signed.dmg", "-notarized.dmg"))
    dmg.rename(final)
    old_checksum.unlink(missing_ok=True)
    hasher = hashlib.sha256()
    with final.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    final.with_suffix(".dmg.sha256").write_text(f"{hasher.hexdigest()}  {final.name}\n")
    print(f"Verified signed and notarized DMG: {final}", flush=True)


if __name__ == "__main__":
    main()
