# Changelog

## Unreleased

- Update Compose Desktop and its UI test library together to 1.12.2; preserve physical Home key navigation using the corrected key mapping.
- Align gRPC Java runtime and code generation at 1.85.1, and Kotlin runtime/code generation at 1.5.0.
- Group future Dependabot updates by Compose, gRPC and Protobuf families.

## 0.1.0-alpha.1 — 2026-11-11

- Shut down or restart one AVD without interrupting another selected session. Reject duplicate and stale instance operations; cancel pending restarts on application close.
- Diagnose process, ADB, authenticated endpoint, gRPC and a bounded first-frame probe; offer explicit reconnection without automatic restart or credential disclosure.
- Recover from an empty screen stream or missing first frame instead of staying in Connecting indefinitely.
- Use provisional product name Android Emulator Hub, keeping Kasane as the repository codename.
- Add an original app icon with reproducible vector source, screenshots and expanded build/signing documentation.
- Separate public SemVer from Apple's internal build number; publish alpha versions as GitHub prereleases.
- Prepare main for a PR/CI workflow with force-push and deletion protection.

## 1.0.0 — 2026-11-11 · Legacy misnumbered preview

Initial macOS preview by Armando Picón (@devpicon).

- Emulator inventory, explicit startup and switching between running sessions.
- Live screen, pointer/keyboard input and capability-based navigation, rotation and fold controls.
- Form-factor icons and AVD creation with configurable resources.
- Confirmed deletion of powered-off AVDs, preserving shared SDK images.
- Explicit stable system-image downloads, progress, cancellation, retry and license refusal.
- Shutdown of listed emulators on close.
- Separate macOS architecture packages, tests, build artifacts and tagged release automation.

At that historical snapshot, Windows, Linux, signing/notarization and individual stop/restart controls remained future work. This tag and its original assets are retained unchanged; the numbering did not represent a stable compatibility contract.
