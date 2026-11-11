#!/usr/bin/env python3
"""Validate the public SemVer separately from Apple's monotonically increasing build number."""
import argparse
import pathlib
import re

SEMVER = re.compile(
    r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-((?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
)


def validate(version: str, build: str, tag: str | None = None) -> bool:
    return (SEMVER.fullmatch(version) is not None and re.fullmatch(r"[1-9]\d*", build) is not None
            and (tag is None or tag == f"v{version}"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag")
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    properties = dict(line.strip().split("=", 1) for line in (root / "gradle.properties").read_text().splitlines()
                      if "=" in line and not line.lstrip().startswith("#"))
    version, build = properties["appVersion"], properties["macBundleVersion"]
    if not validate(version, build, args.tag):
        raise SystemExit("Invalid SemVer, macOS build number or version tag mismatch.")
    print(f"Product {version}; macOS build {build}")
