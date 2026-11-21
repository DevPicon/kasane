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

/** Owns private temporary recordings; saving must preserve both files on failure. */
interface CaptureFiles {
    suspend fun createVideo(): String
    suspend fun exists(path: String): Boolean
    suspend fun save(path: String, destination: String)
    suspend fun discard(path: String)
}

/** Close must wait for an explicit user decision; it is not a session/transport failure. */
class PendingCaptureExport(message: String) : Exception(message)
