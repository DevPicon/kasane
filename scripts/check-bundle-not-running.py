#!/usr/bin/env python3
"""Refuse replacing a live packaged launcher; never stop or alter processes."""
import pathlib
import subprocess
import sys


def check_bundle(bundle):
    executable = bundle / "Contents/MacOS" / bundle.stem
    if not executable.exists():
        return
    result = subprocess.run(["/usr/sbin/lsof", "-t", str(executable.resolve())], capture_output=True, text=True)
    if result.returncode == 0 and result.stdout.strip():
        raise SystemExit("Close the app running from this build output before rebuilding it. Installed copies in other paths can remain open.")
    if result.returncode != 1 or result.stdout.strip() or result.stderr.strip():
        raise SystemExit("Could not safely check whether the build bundle is in use; packaging stopped before replacing it.")


if __name__ == "__main__":
    check_bundle(pathlib.Path(sys.argv[1]))
