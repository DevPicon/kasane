# Architecture

Kasane currently targets macOS through Kotlin/JVM and Compose Desktop. It uses proportional Clean Architecture in one Gradle module:

- `core`: emulator/session identities, capabilities, AVD creation validation and SDK operation contracts. No Compose, process or gRPC dependencies.
- `infrastructure`: SDK/AVD discovery, command-line tools, process ownership, hardware metadata and the direct authenticated Emulator gRPC transport.
- `presentation`: observable state holders and state-driven Compose UI. UI events enter lifecycle-owned actors; asynchronous work is cancelled and awaited during shutdown.
- `Main.kt`: dependency composition and window lifecycle.

Two running emulators can be retained while the main panel displays one selected device. Selection releases input in the previous session and rejects stale frames/events. Display geometry and orientation are part of surface identity. Fold transitions fade out/in and block input until geometry is ready.

AVD creation stages files before exclusive publication. Deletion requires an offline AVD and explicit confirmation, revalidates state and verifies CLI results. Image downloads use the stable SDK catalog for the host ABI, do not reinstall present packages, refuse automatic license acceptance and verify installed files. Cancelling SDK work terminates only owned subprocesses.

The protocol in `src/main/proto` is pinned to Android Emulator 36.4.9. Endpoint credentials stay in memory and must never enter diagnostics or public logs. Linux and Windows need platform discovery/process implementations and runtime validation; they are not supported by this increment.
