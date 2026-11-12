#!/usr/bin/env python3
"""Finalize public metadata and renew only the generated app's outer signature.

Compose signs the nested Java/runtime/native code first. Developer ID renewal is
explicit, restricted to this checkout's build output, and must match that signer.
"""
import argparse
import pathlib
import plistlib
import re
import subprocess


def final_signature_command(bundle, expected_bundle, signature_info, signing_identity=None):
    if bundle.resolve() != expected_bundle.resolve():
        raise ValueError("Refusing to modify a bundle outside the expected local build output.")
    authorities = [line.removeprefix("Authority=") for line in signature_info.splitlines()
                   if line.startswith("Authority=")]
    if signing_identity:
        identity = signing_identity if signing_identity.startswith("Developer ID Application: ") else f"Developer ID Application: {signing_identity}"
        match = re.fullmatch(r"Developer ID Application: .+ \(([A-Z0-9]{10})\)", identity)
        if not match or not authorities or authorities[0] != identity:
            raise ValueError("Generated bundle must already be signed by the requested Developer ID Application identity.")
        if f"TeamIdentifier={match.group(1)}" not in signature_info.splitlines():
            raise ValueError("Generated bundle team does not match the requested signing identity.")
        return ["codesign", "--force", "--sign", identity, "--timestamp", "--options", "runtime",
                "--preserve-metadata=entitlements,requirements", str(bundle)]
    if authorities:
        raise ValueError("Refusing to replace an identity signature without explicit matching Developer ID renewal.")
    return ["codesign", "--force", "--sign", "-", str(bundle)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=pathlib.Path)
    parser.add_argument("--signing-identity")
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    properties = dict(line.strip().split("=", 1) for line in (root / "gradle.properties").read_text().splitlines()
                      if "=" in line and not line.lstrip().startswith("#"))
    expected = root / "build/compose/binaries/main/app" / f"{properties['appDisplayName']}.app"
    signature = subprocess.run(["codesign", "-dv", "--verbose=4", str(args.bundle)],
                               capture_output=True, text=True, check=True)
    try:
        command = final_signature_command(args.bundle, expected, signature.stderr, args.signing_identity)
    except ValueError as error:
        raise SystemExit(str(error)) from error
    subprocess.run(["codesign", "--verify", "--deep", "--strict", str(args.bundle)], check=True, capture_output=True)
    info = args.bundle / "Contents/Info.plist"
    values = plistlib.loads(info.read_bytes())
    semantic = properties["appVersion"]
    values["CFBundleShortVersionString"] = semantic.split("-", 1)[0].split("+", 1)[0]
    values["CFBundleVersion"] = properties["macBundleVersion"]
    values["KasaneSemanticVersion"] = semantic
    info.write_bytes(plistlib.dumps(values, sort_keys=False))
    subprocess.run(command, check=True, capture_output=True)
    subprocess.run(["codesign", "--verify", "--deep", "--strict", str(args.bundle)], check=True, capture_output=True)
    kind = "Developer ID" if args.signing_identity else "ad-hoc"
    print(f"Product {semantic}; macOS build {values['CFBundleVersion']}; {kind} bundle verified")


if __name__ == "__main__":
    main()
