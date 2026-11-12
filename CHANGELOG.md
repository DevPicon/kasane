# Changelog

## Unreleased · 0.1.0-alpha.5

- Move capture actions to the top toolbar, using vector camera/video/stop icons with localized tooltips, accessibility labels and keyboard focus.
- Start recording in a private temporary file and ask for a destination after finalization. Keep canceled/failed exports pending for Save or confirmed Discard; block closure while a video remains pending.
- Add English (default), Spanish and Portuguese with a persistent language selector; localize Hub messages, dialogs, menus and device controls while preserving user/SDK data.

## 0.1.0-alpha.4 · Local development increment

- Save device-resolution PNG screenshots from the selected emulator through a native save dialog.
- Record MP4 video with optional touch indicators, elapsed time, explicit stop and a three-minute limit.
- Restore the previous Android touch-indicator setting; finalize recording before individual shutdown/restart or closing the Hub.
- Keep recordings tied to their original instance and disable its rotation/posture controls while recording. Preserve existing files and clean up owned temporary files/processes.

## 0.1.0-alpha.3 · Local notarized candidate

- Add opt-in local Developer ID signing for the app, bundled runtime and native libraries, preserving hardened runtime and public version metadata.
- Add a Keychain-profile notarization flow with separate app/DMG acceptance, ticket validation, Gatekeeper checks and final checksums.
- Keep signing credentials out of ordinary CI; reject identity downgrades, unexpected teams and modification of delivered bundles.

## 0.1.0-alpha.2 — 2026-11-11

- Add Acerca de via the native macOS application menu and Help menu, with version and authorship.
- Package a read-only test DMG with the self-contained app, Applications shortcut and private evaluation instructions.
- Set the macOS Dock icon explicitly at startup, including direct launcher and development runs.

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
