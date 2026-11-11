package dev.picon.androidhub.core

import kotlinx.coroutines.flow.Flow

data class RunningEmulator(val serial: String, val name: String, val instanceId: String = serial, val avdId: String? = null)

enum class DeviceStatus { OFF, STARTING, READY, STOPPING, RESTARTING, ERROR }

enum class FormFactor { HANDSET, FOLDABLE, TABLET, WEARABLE, TV, AUTOMOTIVE, UNKNOWN }

data class AvailableEmulator(
    val id: String,
    val name: String,
    val api: String,
    val status: DeviceStatus,
    val running: RunningEmulator? = null,
    val error: String? = null,
    val formFactor: FormFactor = FormFactor.UNKNOWN,
)

interface EmulatorInventory : RunningEmulators {
    suspend fun list(): List<AvailableEmulator>
    suspend fun start(id: String): RunningEmulator
    suspend fun delete(id: String)
}

data class DisplaySize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0)
    }
}

/** Coordinates normalized to the visible display, independent of host density. */
data class TouchInput(val x: Float, val y: Float, val pressed: Boolean, val surface: DisplaySurface? = null) {
    init {
        require(x in 0f..1f && y in 0f..1f)
    }
}

data class ScreenFrame(
    val serial: String,
    val size: DisplaySize,
    val rgba: ByteArray,
    val generatedAtMicros: Long,
    val surface: DisplaySurface = DisplaySurface(size),
)

enum class FoldPosture { FOLDED, HALF_OPEN, OPEN, FLIPPED, TENT }

sealed interface EmulatorCommand {
    data object Back : EmulatorCommand
    data object Home : EmulatorCommand
    data object Recents : EmulatorCommand
    data object Rotate : EmulatorCommand
    data class SetPosture(val posture: FoldPosture) : EmulatorCommand
}

data class EmulatorCapabilities(
    val navigation: Boolean = false,
    val rotation: Boolean = false,
    val postures: Set<FoldPosture> = emptySet(),
) {
    fun supports(command: EmulatorCommand): Boolean = when (command) {
        EmulatorCommand.Back, EmulatorCommand.Home, EmulatorCommand.Recents -> navigation
        EmulatorCommand.Rotate -> rotation
        is EmulatorCommand.SetPosture -> command.posture in postures
    }
}

data class DisplaySurface(
    val physicalSize: DisplaySize,
    val displayId: Int = 0,
    val posture: FoldPosture? = null,
    val rotation: Int = 0,
)

interface RunningEmulators {
    suspend fun discover(): List<RunningEmulator>
    suspend fun connect(serial: String): EmulatorSession
    suspend fun stop(emulator: RunningEmulator)
}

interface EmulatorSession : AutoCloseable {
    val emulator: RunningEmulator
    val frames: Flow<ScreenFrame>
    suspend fun capabilities(): EmulatorCapabilities
    suspend fun execute(command: EmulatorCommand, surface: DisplaySurface)
    suspend fun touch(input: TouchInput)
    suspend fun key(key: String, surface: DisplaySurface? = null)
    suspend fun text(text: String, surface: DisplaySurface? = null)
    suspend fun releaseInput()
}

class SessionFailure(message: String, cause: Exception? = null) : Exception(message, cause)
