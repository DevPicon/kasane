# Architecture

Kasane currently targets macOS through Kotlin/JVM and Compose Desktop. It uses proportional Clean Architecture in one Gradle module:

- `core`: emulator/session identities, capabilities, AVD creation validation and SDK operation contracts. No Compose, process or gRPC dependencies.
- `infrastructure`: SDK/AVD discovery, command-line tools, process ownership, hardware metadata and the direct authenticated Emulator gRPC transport.
- `presentation`: observable state holders and state-driven Compose UI. UI events enter lifecycle-owned actors; asynchronous work is cancelled and awaited during shutdown.
- `Main.kt`: dependency composition and window lifecycle.

Two running emulators can be retained while the main panel displays one selected device. Selection releases input in the previous session and rejects stale frames/events. Display geometry and orientation are part of surface identity. Fold transitions fade out/in and block input until geometry is ready.

AVD creation stages files before exclusive publication. Deletion requires an offline AVD and explicit confirmation, revalidates state and verifies CLI results. Image downloads use the stable SDK catalog for the host ABI, do not reinstall present packages, refuse automatic license acceptance and verify installed files. Cancelling SDK work terminates only owned subprocesses.

The protocol in `src/main/proto` is pinned to Android Emulator 36.4.9. Endpoint credentials stay in memory and must never enter diagnostics or public logs. Linux and Windows need platform discovery/process implementations and runtime validation; they are not supported by this increment.

Individual power operations run as window-owned child jobs per AVD. Closing cancels/awaits these jobs before refreshing the inventory and shutting down remaining instances. A restart verifies shutdown before starting another process and preserves the AVD files. Errors remain visible per device, and results/events are correlated by instance identity.

Transport diagnosis opens a separate read-only channel, checks five stages with bounded deadlines and never takes the exclusive input lock. It retains timings and controlled messages, not frame content, credentials or arbitrary server errors. Changing selection/instance or closing cancels the probe and ignores late results. The screen connection also has a first-frame deadline and treats an empty completed stream as a recoverable failure.
