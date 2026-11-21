#!/usr/bin/env python3
"""Notarize a Developer ID build using Keychain credentials or an App Store Connect API key.

Only prints public submission IDs/status. Keys/passwords are never arguments.
Local runs keep artifacts in build/notarization unless --output-dir is specified.
"""
import argparse
import hashlib
import json
import os
import pathlib
import platform
import plistlib
import shutil
import subprocess
import sys
import tempfile
import uuid


def run(arguments, **kwargs):
    return subprocess.run(arguments, check=True, **kwargs)


def build_auth_args(keychain_profile=None, api_key=None, key_id=None, issuer=None):
    has_api_flags = any(value is not None for value in (api_key, key_id, issuer))
    if keychain_profile and has_api_flags:
        raise SystemExit("Use either --keychain-profile or --api-key/--key-id/--issuer, not both.")
    if keychain_profile:
        return ["--keychain-profile", keychain_profile]
    if has_api_flags:
        if not (api_key and key_id and issuer):
            raise SystemExit("API key notarization requires --api-key, --key-id and --issuer together.")
        key_path = pathlib.Path(api_key)
        if not key_path.is_file():
            raise SystemExit(f"Notarization API key file not found: {key_path}")
        return ["--key", str(key_path), "--key-id", key_id, "--issuer", issuer]
    raise SystemExit("Provide either --keychain-profile or --api-key/--key-id/--issuer.")


def normalize_auth_args(auth_or_profile):
    if isinstance(auth_or_profile, str):
        return ["--keychain-profile", auth_or_profile]
    return list(auth_or_profile)


def write_sha256(target):
    hasher = hashlib.sha256()
    with target.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    checksum_path = target.with_name(f"{target.name}.sha256")
    checksum_path.write_text(f"{hasher.hexdigest()}  {target.name}\n")
    return checksum_path


def submit(artifact, auth_or_profile, working, label):
    auth_args = normalize_auth_args(auth_or_profile)
    result = subprocess.run(["xcrun", "notarytool", "submit", str(artifact), *auth_args,
                             "--wait", "--timeout", "30m", "--output-format", "json"], capture_output=True, text=True)
    try:
        response = json.loads(result.stdout)
    except json.JSONDecodeError:
        raise SystemExit("Notarization did not return a submission result. Check the notarization credentials and network; no artifact was released.")
    (working / f"{label}-submission.json").write_text(json.dumps(response, indent=2))
    submission_id = response.get("id")
    try:
        uuid.UUID(submission_id)
    except (ValueError, TypeError, AttributeError):
        raise SystemExit("Notarization did not provide a valid submission ID; no artifact was released.")
    print(f"{label}: submission {submission_id}, status {response.get('status')}", flush=True)
    if submission_id:
        log = run(["xcrun", "notarytool", "log", submission_id, *auth_args], capture_output=True, text=True)
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
    parser.add_argument("--keychain-profile")
    parser.add_argument("--api-key")
    parser.add_argument("--key-id")
    parser.add_argument("--issuer")
    parser.add_argument("--output-dir", type=pathlib.Path)
    parser.add_argument("--zip-app", action="store_true", help="Also write a notarized .app ZIP and SHA-256 to --output-dir.")
    args = parser.parse_args()
    auth_args = build_auth_args(args.keychain_profile, args.api_key, args.key_id, args.issuer)
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
    run(["xcrun", "notarytool", "history", *auth_args, "--output-format", "json"],
        stdout=subprocess.DEVNULL)
    parent = root / "build/notarization"
    parent.mkdir(parents=True, exist_ok=True)
    working = pathlib.Path(tempfile.mkdtemp(prefix=f"{properties['appVersion']}-", dir=parent))
    app = working / source.name
    shutil.copytree(source, app, symlinks=True)
    archive = working / "app-for-notarization.zip"
    run(["ditto", "-c", "-k", "--keepParent", str(app), str(archive)])
    print(f"Working directory: {working}", flush=True)
    submit(archive, auth_args, working, "app")
    run(["xcrun", "stapler", "staple", str(app)])
    run(["xcrun", "stapler", "validate", str(app)])
    run(["codesign", "--verify", "--deep", "--strict", str(app)], capture_output=True)
    run(["spctl", "--assess", "--type", "execute", "--verbose=2", str(app)])
    run([sys.executable, str(root / "scripts/package-test-dmg.py"), "--bundle", str(app),
         "--output-dir", str(working), "--signing-identity", identity])
    dmg = next(working.glob("*-signed.dmg"))
    submit(dmg, auth_args, working, "dmg")
    run(["xcrun", "stapler", "staple", str(dmg)])
    run(["xcrun", "stapler", "validate", str(dmg)])
    run(["codesign", "--verify", "--strict", str(dmg)], capture_output=True)
    run(["hdiutil", "verify", str(dmg)])
    run(["spctl", "--assess", "--type", "open", "--context", "context:primary-signature", "--verbose=2", str(dmg)])
    old_checksum = dmg.with_suffix(".dmg.sha256")
    final = dmg.with_name(dmg.name.replace("-signed.dmg", "-notarized.dmg"))
    dmg.rename(final)
    old_checksum.unlink(missing_ok=True)
    final_checksum = write_sha256(final)
    if args.output_dir:
        args.output_dir.mkdir(parents=True, exist_ok=True)
        exported_dmg = args.output_dir / final.name
        shutil.copy2(final, exported_dmg)
        write_sha256(exported_dmg)
        if args.zip_app:
            arch = {"arm64": "arm64", "x86_64": "x64"}.get(platform.machine())
            if arch is None:
                raise SystemExit("A macOS arm64 or x64 host is required.")
            zip_path = args.output_dir / f"kasane-{properties['appVersion']}-macos-{arch}-notarized.zip"
            run(["ditto", "-c", "-k", "--sequesterRsrc", "--keepParent", str(app), str(zip_path)])
            write_sha256(zip_path)
    print(f"Verified signed and notarized DMG: {final} ({final_checksum.name})", flush=True)


if __name__ == "__main__":
    main()
