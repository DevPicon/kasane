package dev.picon.androidhub.presentation

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.picon.androidhub.core.EmulatorSession
import dev.picon.androidhub.core.RunningEmulator
import dev.picon.androidhub.core.EmulatorInventory
import dev.picon.androidhub.core.AvailableEmulator
import dev.picon.androidhub.core.DeviceStatus
import dev.picon.androidhub.core.ScreenFrame
import dev.picon.androidhub.core.SessionFailure
import dev.picon.androidhub.core.TouchInput
import dev.picon.androidhub.core.DisplaySurface
import dev.picon.androidhub.core.FoldPosture
import dev.picon.androidhub.core.EmulatorCapabilities
import dev.picon.androidhub.core.EmulatorCommand
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

data class HubUiState(
    val emulators: List<RunningEmulator> = emptyList(),
    val devices: List<AvailableEmulator> = emptyList(),
    val selectedDeviceId: String? = null,
    val selected: RunningEmulator? = null,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val message: String = "Buscando emuladores configurados…",
    val posture: FoldPosture? = null,
    val closing: Boolean = false,
    val closed: Boolean = false,
    val closeError: String? = null,
    val capabilities: EmulatorCapabilities = EmulatorCapabilities(),
    val controlPending: Boolean = false,
    val controlError: String? = null,
    val deleteTarget: AvailableEmulator? = null,
    val deleting: Boolean = false,
    val deleteError: String? = null,
)

data class RenderedFrame(val serial: String, val bitmap: ImageBitmap, val surface: DisplaySurface)

sealed interface HubAction {
    data class RequestDelete(val id: String) : HubAction
    data object CancelDelete : HubAction
    data object ConfirmDelete : HubAction
    data object Refresh : HubAction
    data object Close : HubAction
    data class SelectDevice(val id: String) : HubAction
    data class Start(val id: String) : HubAction
    data class Created(val id: String) : HubAction
    data class InventoryLoaded(val generation: Long, val devices: List<AvailableEmulator>) : HubAction
    data class InventoryFailed(val generation: Long, val message: String) : HubAction
    data class Select(val emulator: RunningEmulator) : HubAction
    data class Touch(val serial: String, val input: TouchInput) : HubAction
    data class Key(val serial: String, val key: String, val surface: DisplaySurface? = null) : HubAction
    data class Text(val serial: String, val text: String, val surface: DisplaySurface? = null) : HubAction
    data class Release(val serial: String) : HubAction
    data class Control(val instanceId: String, val command: EmulatorCommand, val surface: DisplaySurface) : HubAction
    data class Failed(val serial: String, val message: String) : HubAction
}

/** UI event boundary. run() is owned and cancelled by the window's LaunchedEffect. */
class HubController(private val emulators: EmulatorInventory, private val beforeClose: suspend () -> Unit = {}) {
    private val mutableState = MutableStateFlow(HubUiState())
    val state = mutableState.asStateFlow()
    private val mutableFrame = MutableStateFlow<RenderedFrame?>(null)
    val frame = mutableFrame.asStateFlow()
    // Preserve press/release ordering. This queue lives only for the lifetime of run().
    private val actions = Channel<HubAction>(Channel.UNLIMITED)

    @Synchronized
    fun dispatch(action: HubAction) {
        if (mutableState.value.closing || mutableState.value.closed) return
        if (action == HubAction.Close) {
            mutableState.update { it.copy(closing = true, closeError = null, message = "Apagando emuladores…") }
        }
        actions.trySend(action)
    }

    suspend fun run(): Unit = coroutineScope {
        var session: EmulatorSession? = null
        var streaming: Job? = null
        var refreshing: Job? = null
        var inventoryGeneration = 0L
        val polling = launch {
            while (isActive) {
                delay(2000)
                dispatch(HubAction.Refresh)
            }
        }

        suspend fun disconnect() {
            streaming?.cancelAndJoin()
            streaming = null
            val previous = session
            session = null
            try {
                previous?.releaseInput()
            } catch (_: Exception) {
                // Cleanup is bounded by the transport deadline and close still runs.
            } finally {
                previous?.close()
                mutableFrame.value = null
                mutableState.update { it.copy(capabilities = EmulatorCapabilities(), controlPending = false, controlError = null) }
            }
        }

        dispatch(HubAction.Refresh)
        try {
            for (action in actions) {
                if (mutableState.value.closing && action != HubAction.Close) continue
                try {
                    when (action) {
                        is HubAction.RequestDelete -> {
                            val target = mutableState.value.devices.firstOrNull { it.id == action.id && it.canDelete() } ?: continue
                            mutableState.update { it.copy(deleteTarget = target, deleteError = null) }
                        }
                        HubAction.CancelDelete -> mutableState.update { it.copy(deleteTarget = null, deleteError = null) }
                        HubAction.ConfirmDelete -> {
                            val target = mutableState.value.deleteTarget ?: continue
                            inventoryGeneration++
                            refreshing?.cancelAndJoin()
                            mutableState.update { it.copy(deleting = true, deleteError = null) }
                            try {
                                val fresh = emulators.list().firstOrNull { it.id == target.id }
                                if (fresh?.canDelete() != true) throw SessionFailure("El AVD ya no está apagado o disponible. Actualiza el inventario.")
                                if (mutableState.value.selectedDeviceId == target.id) withContext(NonCancellable) { disconnect() }
                                // Once confirmed, deletion is allowed to finish before processing Close.
                                withContext(NonCancellable) { emulators.delete(target.id) }
                                mutableState.update { current ->
                                    val selectedDeleted = current.selectedDeviceId == target.id
                                    current.copy(deleteTarget = null, deleting = false, deleteError = null,
                                        devices = current.devices.filterNot { it.id == target.id },
                                        selectedDeviceId = if (selectedDeleted) null else current.selectedDeviceId,
                                        selected = if (selectedDeleted) null else current.selected,
                                        connected = if (selectedDeleted) false else current.connected,
                                        connecting = if (selectedDeleted) false else current.connecting,
                                        message = if (selectedDeleted) "Emulador eliminado." else current.message)
                                }
                            } catch (error: CancellationException) { throw error }
                            catch (error: Exception) { mutableState.update { it.copy(deleting = false, deleteError = userMessage(error)) } }
                            dispatch(HubAction.Refresh)
                        }
                        HubAction.Close -> {
                            beforeClose()
                            inventoryGeneration++
                            refreshing?.cancelAndJoin()
                            withContext(NonCancellable) { disconnect() }
                            val failures = mutableState.value.emulators.distinctBy { it.instanceId }.map { emulator ->
                                async {
                                    try {
                                        emulators.stop(emulator)
                                        null
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        emulator to userMessage(error)
                                    }
                                }
                            }.awaitAll().filterNotNull()
                            if (failures.isEmpty()) {
                                mutableState.update { it.copy(closing = false, closed = true, connected = false, emulators = emptyList()) }
                                break
                            }
                            mutableState.update {
                                it.copy(closing = false, connecting = false, connected = false,
                                    emulators = failures.map { failure -> failure.first },
                                    selected = it.selected?.takeIf { selected -> failures.any { failure -> failure.first == selected } },
                                    closeError = failures.joinToString("\n") { failure -> "${failure.first.name}. ${failure.second}" },
                                    message = "No se pudieron apagar todos los emuladores.")
                            }
                        }
                        HubAction.Refresh -> {
                            if (refreshing?.isActive != true) {
                                val generation = inventoryGeneration
                                refreshing = launch {
                                    try {
                                        dispatch(HubAction.InventoryLoaded(generation, emulators.list()))
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        dispatch(HubAction.InventoryFailed(generation, userMessage(error)))
                                    }
                                }
                            }
                        }
                        is HubAction.Created -> {
                            inventoryGeneration++
                            refreshing?.cancelAndJoin()
                            withContext(NonCancellable) { disconnect() }
                            mutableState.update { it.copy(selectedDeviceId = action.id, selected = null, connecting = false, connected = false,
                                posture = null, closeError = null, message = "Emulador creado. Actualizando inventario…") }
                            dispatch(HubAction.Refresh)
                        }
                        is HubAction.InventoryFailed -> if (action.generation == inventoryGeneration) {
                            mutableState.update { it.copy(message = action.message) }
                        }
                        is HubAction.InventoryLoaded -> if (action.generation == inventoryGeneration) {
                            val current = mutableState.value
                            val devices = action.devices.map { device ->
                                val previous = current.devices.firstOrNull { it.id == device.id }
                                if (device.status == DeviceStatus.OFF && previous?.status == DeviceStatus.ERROR) {
                                    device.copy(status = DeviceStatus.ERROR, error = previous.error)
                                } else device
                            }
                            val selected = devices.firstOrNull { it.id == current.selectedDeviceId }
                            val instanceChanged = current.selected != null && selected?.running?.instanceId != current.selected.instanceId
                            if (instanceChanged) withContext(NonCancellable) { disconnect() }
                            mutableState.update {
                                it.copy(devices = devices, emulators = devices.mapNotNull { device -> device.running },
                                    selected = if (instanceChanged) null else it.selected,
                                    connected = if (instanceChanged) false else it.connected,
                                    connecting = if (instanceChanged) false else it.connecting,
                                    message = when (selected?.status) {
                                        DeviceStatus.OFF -> "Emulador apagado."
                                        DeviceStatus.STARTING -> "Iniciando Android…"
                                        DeviceStatus.ERROR -> selected.error ?: "No se pudo iniciar el emulador."
                                        else -> if (devices.isEmpty()) "No hay AVD configurados. Usa + Crear para agregar uno." else it.message
                                    })
                            }
                            if (current.selectedDeviceId == null && devices.isNotEmpty()) {
                                dispatch(HubAction.SelectDevice(devices.first().id))
                            } else if (selected?.status == DeviceStatus.READY && selected.running != null && mutableState.value.selected == null) {
                                dispatch(HubAction.Select(selected.running))
                            }
                        }
                        is HubAction.SelectDevice -> {
                            val device = mutableState.value.devices.firstOrNull { it.id == action.id } ?: continue
                            withContext(NonCancellable) { disconnect() }
                            mutableState.update { it.copy(selectedDeviceId = device.id, selected = null,
                                connected = false, connecting = false, posture = null, closeError = null,
                                message = when (device.status) {
                                    DeviceStatus.OFF -> "Emulador apagado."
                                    DeviceStatus.STARTING -> "Iniciando Android…"
                                    DeviceStatus.ERROR -> device.error ?: "No se pudo iniciar el emulador."
                                    DeviceStatus.READY -> "Conectando…"
                                }) }
                            if (device.status == DeviceStatus.READY && device.running != null) dispatch(HubAction.Select(device.running))
                        }
                        is HubAction.Start -> {
                            if (mutableState.value.deleteTarget != null) continue
                            val device = mutableState.value.devices.firstOrNull { it.id == action.id } ?: continue
                            if (device.status == DeviceStatus.STARTING || device.status == DeviceStatus.READY) continue
                            inventoryGeneration++
                            refreshing?.cancelAndJoin()
                            withContext(NonCancellable) { disconnect() }
                            mutableState.update { it.copy(selectedDeviceId = device.id, selected = null, connected = false,
                                connecting = false, closeError = null, posture = null, message = "Iniciando Android…",
                                devices = it.devices.map { entry -> if (entry.id == device.id) entry.copy(status = DeviceStatus.STARTING, error = null) else entry }) }
                            try {
                                val instance = emulators.start(device.id)
                                mutableState.update { it.copy(
                                    emulators = it.emulators.filter { entry -> entry.avdId != device.id } + instance,
                                    devices = it.devices.map { entry -> if (entry.id == device.id) entry.copy(running = instance) else entry }) }
                                dispatch(HubAction.Refresh)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                val message = userMessage(error)
                                mutableState.update { it.copy(message = message, devices = it.devices.map { entry ->
                                    if (entry.id == device.id) entry.copy(status = DeviceStatus.ERROR, error = message) else entry
                                }) }
                            }
                        }
                        is HubAction.Select -> {
                            val deviceId = mutableState.value.devices.firstOrNull { it.running?.instanceId == action.emulator.instanceId }?.id
                            if (deviceId != mutableState.value.selectedDeviceId) continue
                            withContext(NonCancellable) { disconnect() }
                            mutableState.update {
                                it.copy(selected = action.emulator, connecting = true, connected = false, message = "Conectando…", posture = null, closeError = null)
                            }
                            val active = emulators.connect(action.emulator.serial)
                            session = active
                            val capabilities = active.capabilities()
                            mutableState.update { it.copy(capabilities = capabilities) }
                            streaming = launch {
                                try {
                                    active.frames.buffer(Channel.CONFLATED).collect { raw ->
                                        val rendered = withContext(Dispatchers.Default) { raw.render() }
                                        mutableFrame.value = rendered
                                        if (!mutableState.value.connected || mutableState.value.posture != raw.surface.posture) {
                                            mutableState.update { it.copy(connecting = false, connected = true, message = "Listo", posture = raw.surface.posture) }
                                        }
                                    }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    dispatch(HubAction.Failed(active.emulator.serial, userMessage(error)))
                                }
                            }
                        }
                        is HubAction.Failed -> if (action.serial == session?.emulator?.serial) {
                            withContext(NonCancellable) { disconnect() }
                            mutableState.update { it.copy(connecting = false, connected = false, message = action.message) }
                        }
                        is HubAction.Touch -> session?.takeIf { it.emulator.serial == action.serial }?.touch(action.input)
                        is HubAction.Key -> session?.takeIf { it.emulator.serial == action.serial }?.key(action.key, action.surface)
                        is HubAction.Text -> session?.takeIf { it.emulator.serial == action.serial }?.text(action.text, action.surface)
                        is HubAction.Release -> session?.takeIf { it.emulator.serial == action.serial }?.releaseInput()
                        is HubAction.Control -> {
                            val active = session ?: continue
                            val current = mutableState.value
                            if (active.emulator.instanceId != action.instanceId || !current.connected ||
                                !current.capabilities.supports(action.command) || mutableFrame.value?.surface != action.surface) continue
                            mutableState.update { it.copy(controlPending = true, controlError = null) }
                            try {
                                active.releaseInput()
                                withTimeout(8000) {
                                    active.execute(action.command, action.surface)
                                    when (val command = action.command) {
                                        EmulatorCommand.Rotate -> frame.filterNotNull().first { it.surface.rotation != action.surface.rotation }
                                        is EmulatorCommand.SetPosture -> frame.filterNotNull().first { it.surface.posture == command.posture }
                                        else -> Unit
                                    }
                                }
                            } catch (_: TimeoutCancellationException) {
                                mutableState.update { it.copy(controlError = "El emulador no confirmó el cambio. Puedes reintentar.") }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                mutableState.update { it.copy(controlError = userMessage(error)) }
                            } finally {
                                mutableState.update { it.copy(controlPending = false) }
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    withContext(NonCancellable) { disconnect() }
                    mutableState.update { it.copy(connecting = false, connected = false, closing = false, message = userMessage(error)) }
                }
            }
        } finally {
            withContext(NonCancellable) {
                polling.cancelAndJoin()
                refreshing?.cancelAndJoin()
                disconnect()
            }
            actions.close()
        }
    }
}

private fun userMessage(error: Exception): String = if (error is SessionFailure) {
    error.message ?: "Error de sesión."
} else {
    "No se pudo completar la operación (${error.javaClass.simpleName})."
}

private fun ScreenFrame.render(): RenderedFrame {
    val image = Image.makeRaster(
        ImageInfo(size.width, size.height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE),
        rgba,
        size.width * 4,
    )
    return RenderedFrame(serial, image.toComposeImageBitmap(), surface)
}

internal fun AvailableEmulator.canDelete(): Boolean = running == null && status in setOf(DeviceStatus.OFF, DeviceStatus.ERROR) && !id.startsWith("running:")
