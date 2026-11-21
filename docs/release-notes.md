Android Emulator Hub **0.1.0-alpha.5** — codename Kasane. Early development preview, with no stable compatibility promise.

- Save device-resolution PNG screenshots and record MP4 videos (up to 180s, optional touch indicators, save/discard prompt on finalization).
- Switch between English (default), Spanish, and Portuguese with a persistent language selector.
- Display a green `Ready` badge beside active device names in the header and sidebar.
- Shut down or restart a single AVD while keeping another session active. Restart retains the AVD and its data; duplicate/stale operations are rejected and closing cancels pending restarts.
- Diagnose process identity, ADB, authenticated endpoint, gRPC and a bounded first-frame probe without interrupting the active screen. Reconnect explicitly; no automatic restart or credential logging.
- Recover from an empty stream or a missing first frame rather than staying in Connecting indefinitely.
- Browse device form factors, create AVDs, download compatible stable system images and delete powered-off AVDs with confirmation.
- Navigate, rotate and change supported foldable postures using the direct authenticated gRPC transport.
- Use the provisional Android Emulator Hub name, an original app icon and product SemVer separated from macOS build numbering.

Choose `arm64` for Apple Silicon or `x64` for Intel. DMG and ZIP packages are available. Each package includes a Java runtime for the Hub. The Android SDK, Emulator, Platform Tools, Command-line Tools and a working SDK JDK remain separate prerequisites. Verify archives using the attached SHA-256 files.

Limitations: macOS only, one visible device screen, no physical devices/audio. On certain Android images, recording a static screen with no visual changes may produce a zero-duration MP4 (no audio, max 180s). Real emulator behavior has been locally validated on Apple Silicon; Intel packages are built in CI. SDK licenses are never accepted automatically. Closing the Hub also stops externally started emulators listed in its inventory.

The older v1.0.0 was a misnumbered private preview. Its original tag and files are preserved; use this 0.x alpha for current development.

Copyright © 2026 Armando Picón (@devpicon on all social networks). Dual-licensed under AGPL-3.0 and a Commercial License. Third-party components retain their own licenses.
