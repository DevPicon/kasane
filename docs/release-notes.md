Kasane brings Android emulator inventory, creation and interaction into one macOS window.

- Switch between running emulator sessions using a direct authenticated gRPC client.
- Navigate, rotate and change supported foldable postures.
- Create AVDs with editable resources and distinct form-factor icons.
- Delete powered-off AVDs after explicit confirmation.
- Download compatible stable system images with progress, cancellation and retry.
- Shut down listed emulators when the app closes.

Choose arm64 for Apple Silicon or x64 for Intel. Packages include a Java runtime; the Android SDK, Command-line Tools and a working SDK JDK are separate prerequisites. Verify ZIPs using the attached SHA-256 checksums.

Preview limitations: macOS only, one visible device screen, no physical devices/audio, unsigned and unnotarized packages. Intel packages are built in CI; real emulator behavior has been locally validated on Apple Silicon. SDK licenses are never accepted automatically. Closing Kasane also stops externally started emulators in its inventory.

Copyright © 2026 Armando Picón (@devpicon on all social networks). Private repository; no project license has been granted. Third-party components retain their own licenses.
