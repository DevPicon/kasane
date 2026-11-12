package dev.picon.androidhub.core

import kotlinx.coroutines.Deferred

/** Local, explicit exports. Implementations must not overwrite existing destinations. */
interface EmulatorCapture {
    suspend fun screenshot(emulator: RunningEmulator, destination: String)
    suspend fun record(emulator: RunningEmulator, destination: String, showTaps: Boolean,
                       stop: Deferred<Unit>, onStarted: () -> Unit)
}

enum class CaptureKind(val extension: String) { SCREENSHOT("png"), VIDEO("mp4") }

fun interface CaptureDestination {
    /** null means that the user cancelled. */
    suspend fun choose(kind: CaptureKind): String?
}
