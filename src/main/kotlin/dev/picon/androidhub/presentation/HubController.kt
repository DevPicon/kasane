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
import dev.picon.androidhub.core.TransportDiagnostics
import dev.picon.androidhub.core.TransportReport
import dev.picon.androidhub.core.DiagnosticCheck
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
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
    val powerErrors: Map<String, String> = emptyMap(),
    val diagnostic: DiagnosticUiState? = null,
)

data class RenderedFrame(val serial: String, val bitmap: ImageBitmap, val surface: DisplaySurface)

sealed interface HubAction {
    data class RequestDelete(val id: String) : HubAction
    data object CancelDelete : HubAction
    data object ConfirmDelete : HubAction
    data object Refresh : HubAction
    data object Close : HubAction
    data class SelectDevice(val id: String) : HubAction
    data class Diagnose(val id: String) : HubAction
    data object CancelDiagnosis : HubAction
    data object Reconnect : HubAction
    data class DiagnosticProgress(val token: Long, val check: DiagnosticCheck) : HubAction
    data class DiagnosticFinished(val token: Long, val report: TransportReport) : HubAction
    data class DiagnosticFailed(val token: Long) : HubAction
    data class Stop(val id: String) : HubAction
    data class Restart(val id: String) : HubAction
    data class PowerFinished(val id: String, val token: Long, val previousInstanceId: String, val instance: RunningEmulator?, val error: String?) : HubAction
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
    data class Failed(val instanceId: String, val message: String) : HubAction
}

/** UI event boundary. run() is owned and cancelled by the window's LaunchedEffect. */
class HubController(private val emulators: EmulatorInventory, private val diagnostics: TransportDiagnostics? = null, private val beforePowerOff: suspend (RunningEmulator) -> Unit = {}, private val allowGeometryChange: (RunningEmulator) -> Boolean = { true }, private val beforeClose: suspend () -> Unit = {}) {
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
        var diagnosticJob: Job? = null
        var diagnosticGeneration = 0L
        var powerGeneration = 0L
        val powerOperations = mutableMapOf<String, PowerOperation>()
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

        suspend fun cancelDiagnostic() {
            diagnosticGeneration++
            diagnosticJob?.cancelAndJoin()
            diagnosticJob = null
            mutableState.update { it.copy(diagnostic = null) }
        }

        dispatch(HubAction.Refresh)
        try {
            for (action in actions) {
                if (mutableState.value.closing && action != HubAction.Close) continue
                try {
                    when (action) {
                        is HubAction.Diagnose -> {
                            val inspector = diagnostics ?: continue
                            val target = mutableState.value.devices.firstOrNull { it.id == action.id } ?: continue
                            if (target.id in powerOperations) continue
                            cancelDiagnostic()
                            val token = ++diagnosticGeneration
                            mutableState.update { it.copy(diagnostic = DiagnosticUiState(target.id, target.name, target.running?.instanceId)) }
                            diagnosticJob = launch {
                                try {
                                    val report = inspector.inspect(target) { dispatch(HubAction.DiagnosticProgress(token, it)) }
                                    dispatch(HubAction.DiagnosticFinished(token, report))
                                } catch (error: CancellationException) { throw error }
                                catch (_: Exception) { dispatch(HubAction.DiagnosticFailed(token)) }
                            }
                        }
                        HubAction.CancelDiagnosis -> cancelDiagnostic()
                        is HubAction.DiagnosticProgress -> if (action.token == diagnosticGeneration) {
                            mutableState.update { current -> current.copy(diagnostic = current.diagnostic?.let {
                                it.copy(checks = it.checks.filterNot { check -> check.stage == action.check.stage } + action.check)
                            }) }
                        }
                        is HubAction.DiagnosticFinished -> if (action.token == diagnosticGeneration) {
                            mutableState.update { current -> current.copy(diagnostic = current.diagnostic?.copy(checking = false, checks = action.report.checks, report = action.report)) }
                        }
                        is HubAction.DiagnosticFailed -> if (action.token == diagnosticGeneration) {
                            mutableState.update { current -> current.copy(diagnostic = current.diagnostic?.copy(checking = false, error = "No se pudo completar la comprobación. Reintenta tras actualizar el inventario.")) }
                        }
                        HubAction.Reconnect -> {
                            val snapshot = mutableState.value
                            val expected = snapshot.diagnostic?.instanceId ?: continue
                            val target = snapshot.devices.firstOrNull { it.id == snapshot.selectedDeviceId && it.running?.instanceId == expected && it.status == DeviceStatus.READY } ?: continue
                            val fresh = emulators.list().firstOrNull { it.id == target.id && it.running?.instanceId == expected && it.status == DeviceStatus.READY }
                            cancelDiagnostic()
                            if (fresh?.running == null) {
                                mutableState.update { it.copy(message = "La instancia cambió. Actualiza el inventario antes de reconectar.") }
                                dispatch(HubAction.Refresh)
                            } else dispatch(HubAction.Select(fresh.running))
                        }

                        is HubAction.Stop, is HubAction.Restart -> {
                            val restart = action is HubAction.Restart
                            val id = if (action is HubAction.Stop) action.id else (action as HubAction.Restart).id
                            if (id in powerOperations || mutableState.value.deleteTarget != null) continue
                            val device = mutableState.value.devices.firstOrNull { it.id == id } ?: continue
                            val expected = device.running ?: continue
                            if (mutableState.value.diagnostic?.deviceId == id) cancelDiagnostic()
                            inventoryGeneration++
                            refreshing?.cancelAndJoin()
                            val selected = mutableState.value.selectedDeviceId == id
                            if (selected) withContext(NonCancellable) { disconnect() }
                            mutableState.update { current -> current.copy(
                                selected = if (selected) null else current.selected,
                                connected = if (selected) false else current.connected,
                                connecting = if (selected) false else current.connecting,
                                powerErrors = current.powerErrors - id,
                                message = if (selected) (if (restart) "Reiniciando emulador…" else "Apagando emulador…") else current.message,
                                devices = current.devices.map { if (it.id == id) it.copy(status = if (restart) DeviceStatus.RESTARTING else DeviceStatus.STOPPING, error = null) else it }) }
                            val token = ++powerGeneration
                            val job = launch {
                                try {
                                    val fresh = emulators.list().firstOrNull { it.id == id }?.running
                                    if (fresh != null && fresh.instanceId != expected.instanceId) throw SessionFailure("La instancia cambió. Actualiza el inventario antes de actuar sobre ella.")
                                    beforePowerOff(expected)
                                    emulators.stop(expected)
                                    currentCoroutineContext().ensureActive()
                                    val remaining = emulators.list().firstOrNull { it.id == id }?.running
                                    if (remaining != null) throw SessionFailure("No se confirmó el apagado, o hay una instancia nueva. No se iniciará otra.")
                                    val next = if (restart && !mutableState.value.closing) {
                                        currentCoroutineContext().ensureActive()
                                        emulators.start(id)
                                    } else null
                                    dispatch(HubAction.PowerFinished(id, token, expected.instanceId, next, null))
                                } catch (error: CancellationException) { throw error }
                                catch (error: Exception) { dispatch(HubAction.PowerFinished(id, token, expected.instanceId, null, userMessage(error))) }
                            }
                            powerOperations[id] = PowerOperation(token, restart, job)
                        }
                        is HubAction.PowerFinished -> {
                            if (powerOperations[action.id]?.token != action.token) continue
                            powerOperations.remove(action.id)
                            inventoryGeneration++
                            refreshing?.cancelAndJoin()
                            mutableState.update { current ->
                                val selected = current.selectedDeviceId == action.id
                                current.copy(powerErrors = if (action.error == null) current.powerErrors - action.id else current.powerErrors + (action.id to action.error),
                                    devices = current.devices.map { device ->
                                    if (device.id != action.id) device else if (action.error != null) device.copy(status = DeviceStatus.ERROR, error = action.error)
                                    else if (device.running != null && device.running.instanceId != action.previousInstanceId && action.instance == null) device
                                    else device.copy(status = if (action.instance == null) DeviceStatus.OFF else DeviceStatus.STARTING, running = action.instance, error = null)
                                }, emulators = if (action.error != null) current.emulators else current.emulators.filterNot { it.instanceId == action.previousInstanceId } + listOfNotNull(action.instance),
                                    message = if (selected) action.error ?: if (action.instance != null) "Iniciando Android…" else "Emulador apagado." else current.message)
                            }
                            dispatch(HubAction.Refresh)
                        }
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
                            cancelDiagnostic()
                            beforeClose()
                            powerOperations.values.forEach { it.job.cancel() }
                            powerOperations.values.forEach { it.job.join() }
                            powerOperations.clear()
                            inventoryGeneration++
                            refreshing?.cancelAndJoin()
                            withContext(NonCancellable) { disconnect() }
                            var inventoryError: String? = null
                            val fresh = try { emulators.list().mapNotNull { it.running } }
                            catch (error: CancellationException) { throw error }
                            catch (error: Exception) { inventoryError = userMessage(error); emptyList() }
                            val failures = (mutableState.value.emulators + fresh).distinctBy { it.instanceId }.map { emulator ->
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
                            if (failures.isEmpty() && inventoryError == null) {
                                mutableState.update { it.copy(closing = false, closed = true, connected = false, emulators = emptyList()) }
                                break
                            }
                            mutableState.update {
                                it.copy(closing = false, connecting = false, connected = false,
                                    emulators = failures.map { failure -> failure.first },
                                    selected = it.selected?.takeIf { selected -> failures.any { failure -> failure.first == selected } },
                                    closeError = (failures.map { failure -> "${failure.first.name}. ${failure.second}" } + listOfNotNull(inventoryError)).joinToString("\n"),
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
                            cancelDiagnostic()
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
                            val openDiagnostic = mutableState.value.diagnostic
                            if (openDiagnostic != null && action.devices.firstOrNull { it.id == openDiagnostic.deviceId }?.let {
                                    it.running?.instanceId != openDiagnostic.instanceId
                                } != false) cancelDiagnostic()
                            val current = mutableState.value
                            val devices = action.devices.map { device ->
                                val previous = current.devices.firstOrNull { it.id == device.id }
                                val operation = powerOperations[device.id]
                                if (operation != null) device.copy(status = if (operation.restart) DeviceStatus.RESTARTING else DeviceStatus.STOPPING)
                                else if (device.status == DeviceStatus.OFF && previous?.status == DeviceStatus.ERROR) {
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
                                        DeviceStatus.STOPPING -> "Apagando emulador…"
                                        DeviceStatus.RESTARTING -> "Reiniciando emulador…"
                                        DeviceStatus.STARTING -> "Iniciando Android…"
                                        DeviceStatus.ERROR -> selected.error ?: "No se pudo iniciar el emulador."
                                        else -> if (devices.isEmpty()) "No hay AVD configurados. Usa + Crear para agregar uno." else it.message
                                    })
                            }
                            if (current.selectedDeviceId == null && devices.isNotEmpty()) {
                                dispatch(HubAction.SelectDevice(devices.first().id))
                            } else if (selected?.status == DeviceStatus.READY && selected.running != null && mutableState.value.selected == null && selected.id !in powerOperations) {
                                dispatch(HubAction.Select(selected.running))
                            }
                        }
                        is HubAction.SelectDevice -> {
                            cancelDiagnostic()
                            val device = mutableState.value.devices.firstOrNull { it.id == action.id } ?: continue
                            withContext(NonCancellable) { disconnect() }
                            mutableState.update { it.copy(selectedDeviceId = device.id, selected = null,
                                connected = false, connecting = false, posture = null, closeError = null,
                                message = when (device.status) {
                                    DeviceStatus.OFF -> "Emulador apagado."
                                    DeviceStatus.STOPPING -> "Apagando emulador…"
                                    DeviceStatus.RESTARTING -> "Reiniciando emulador…"
                                    DeviceStatus.STARTING -> "Iniciando Android…"
                                    DeviceStatus.ERROR -> device.error ?: "No se pudo iniciar el emulador."
                                    DeviceStatus.READY -> "Conectando…"
                                }) }
                            if (device.status == DeviceStatus.READY && device.running != null) dispatch(HubAction.Select(device.running))
                        }
                        is HubAction.Start -> {
                            if (mutableState.value.deleteTarget != null) continue
                            val device = mutableState.value.devices.firstOrNull { it.id == action.id } ?: continue
                            if (device.running != null || device.id in powerOperations || device.status == DeviceStatus.STARTING || device.status == DeviceStatus.READY) continue
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
                            if (deviceId != mutableState.value.selectedDeviceId || deviceId in powerOperations) continue
                            withContext(NonCancellable) { disconnect() }
                            mutableState.update {
                                it.copy(selected = action.emulator, connecting = true, connected = false, message = "Conectando…", posture = null, closeError = null)
                            }
                            val active = emulators.connect(action.emulator.serial)
                            if (active.emulator.instanceId != action.emulator.instanceId) {
                                active.close()
                                throw SessionFailure("La instancia cambió durante la conexión. Actualiza el inventario y reintenta.")
                            }
                            session = active
                            val capabilities = active.capabilities()
                            mutableState.update { it.copy(capabilities = capabilities) }
                            streaming = launch {
                                val firstFrameDeadline = launch {
                                    delay(12_000)
                                    dispatch(HubAction.Failed(active.emulator.instanceId, "No llegó el primer frame. Abre Diagnóstico para comprobar el transporte."))
                                }
                                try {
                                    active.frames.buffer(Channel.CONFLATED).collect { raw ->
                                        firstFrameDeadline.cancel()
                                        val rendered = withContext(Dispatchers.Default) { raw.render() }
                                        mutableFrame.value = rendered
                                        if (!mutableState.value.connected || mutableState.value.posture != raw.surface.posture) {
                                            mutableState.update { it.copy(connecting = false, connected = true, message = "Listo", posture = raw.surface.posture) }
                                        }
                                    }
                                    throw SessionFailure("El stream de pantalla terminó. Abre Diagnóstico para comprobar el transporte.")
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    dispatch(HubAction.Failed(active.emulator.instanceId, userMessage(error)))
                                } finally { firstFrameDeadline.cancel() }
                            }
                        }
                        is HubAction.Failed -> if (action.instanceId == session?.emulator?.instanceId) {
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
                            if ((action.command == EmulatorCommand.Rotate || action.command is EmulatorCommand.SetPosture) &&
                                !allowGeometryChange(active.emulator)) continue
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
                diagnosticJob?.cancelAndJoin()
                powerOperations.values.forEach { it.job.cancel() }
                powerOperations.values.forEach { it.job.join() }
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

private data class PowerOperation(val token: Long, val restart: Boolean, val job: Job)
