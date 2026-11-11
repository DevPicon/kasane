package dev.picon.androidhub.presentation

import dev.picon.androidhub.core.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HubControllerTest {
    @Test
    fun `delete requires an offline target and confirmation with cancellation and retry`() = runTest {
        val source = FakeEmulators(offline = true)
        val controller = HubController(source)
        val job = launch { controller.run() }
        runCurrent()
        controller.dispatch(HubAction.RequestDelete("unknown"))
        controller.dispatch(HubAction.ConfirmDelete)
        runCurrent()
        assertTrue(source.deleted.isEmpty())
        controller.dispatch(HubAction.RequestDelete("Test_AVD"))
        runCurrent()
        assertEquals("Test_AVD", controller.state.value.deleteTarget?.id)
        controller.dispatch(HubAction.CancelDelete)
        controller.dispatch(HubAction.ConfirmDelete)
        runCurrent()
        assertTrue(source.deleted.isEmpty())
        controller.dispatch(HubAction.RequestDelete("Test_AVD"))
        source.deleteFails = true
        controller.dispatch(HubAction.ConfirmDelete)
        runCurrent()
        assertEquals("Deletion failed", controller.state.value.deleteError)
        assertEquals(1, controller.state.value.devices.size)
        source.deleteFails = false
        controller.dispatch(HubAction.ConfirmDelete)
        controller.dispatch(HubAction.ConfirmDelete)
        runCurrent()
        assertEquals(listOf("Test_AVD"), source.deleted)
        assertEquals(null, controller.state.value.selectedDeviceId)
        assertTrue(controller.state.value.devices.isEmpty())
        job.cancelAndJoin()
    }

    @Test
    fun `delete rejects a running target and one started externally before confirmation`() = runTest {
        val source = FakeEmulators(offline = true)
        val controller = HubController(source)
        val job = launch { controller.run() }
        runCurrent()
        controller.dispatch(HubAction.RequestDelete("Test_AVD"))
        runCurrent()
        source.start("Test_AVD")
        controller.dispatch(HubAction.ConfirmDelete)
        runCurrent()
        assertTrue(source.deleted.isEmpty())
        assertTrue(controller.state.value.deleteError != null)
        controller.dispatch(HubAction.CancelDelete)
        controller.dispatch(HubAction.RequestDelete("Test_AVD"))
        runCurrent()
        assertEquals(null, controller.state.value.deleteTarget)
        job.cancelAndJoin()
    }

    @Test
    fun `controls reject old instance and unsupported action and recover after command failure`() = runTest {
        val source = FakeEmulators()
        val controller = HubController(source)
        val running = launch { controller.run() }
        runCurrent()
        val surface = DisplaySurface(DisplaySize(1, 2))
        source.session.screen.emit(ScreenFrame(source.device.serial, DisplaySize(1, 2), ByteArray(8), 0, surface))
        // Raster conversion runs on Default, so await the actual frame rather than virtual time.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5000) { while (!controller.state.value.connected) kotlinx.coroutines.delay(10) }
        }
        controller.dispatch(HubAction.Control("old-instance", EmulatorCommand.Home, surface))
        controller.dispatch(HubAction.Control(source.device.instanceId, EmulatorCommand.Rotate, surface))
        controller.dispatch(HubAction.Control(source.device.instanceId, EmulatorCommand.Home, surface.copy(rotation = 1)))
        runCurrent()
        assertTrue(source.session.commands.isEmpty())
        source.session.commandFails = true
        controller.dispatch(HubAction.Control(source.device.instanceId, EmulatorCommand.Home, surface))
        runCurrent()
        assertTrue(controller.state.value.controlError!!.contains("rechazada"))
        assertTrue(controller.state.value.connected)
        assertFalse(controller.state.value.controlPending)
        source.session.commandFails = false
        controller.dispatch(HubAction.Control(source.device.instanceId, EmulatorCommand.Recents, surface))
        runCurrent()
        assertEquals(listOf<EmulatorCommand>(EmulatorCommand.Recents), source.session.commands)
        assertEquals(null, controller.state.value.controlError)
        running.cancelAndJoin()
    }

    @Test
    fun `configured device stays off until requested and duplicate starts launch once`() = runTest {
        val source = FakeEmulators(offline = true)
        val controller = HubController(source)
        val running = launch { controller.run() }
        runCurrent()
        assertEquals(DeviceStatus.OFF, controller.state.value.devices.single().status)
        assertEquals(0, source.startCalls)
        controller.dispatch(HubAction.Start("Test_AVD"))
        controller.dispatch(HubAction.Start("Test_AVD"))
        runCurrent()
        assertEquals(1, source.startCalls)
        assertEquals(DeviceStatus.STARTING, controller.state.value.devices.single().status)
        controller.dispatch(HubAction.Close)
        runCurrent()
        running.join()
        assertEquals(listOf(source.device), source.stopped)
        assertTrue(controller.state.value.closed)
    }

    @Test
    fun `device connects only after Android is ready`() = runTest {
        val source = FakeEmulators(offline = true)
        val controller = HubController(source)
        val running = launch { controller.run() }
        runCurrent()
        controller.dispatch(HubAction.Start("Test_AVD"))
        runCurrent()
        assertEquals(0, source.connectCalls)
        source.bootReady = true
        controller.dispatch(HubAction.Refresh)
        runCurrent()
        assertEquals(1, source.connectCalls)
        assertEquals(source.device, controller.state.value.selected)
        assertEquals(DeviceStatus.READY, controller.state.value.devices.single().status)
        running.cancelAndJoin()
    }

    @Test
    fun `start error survives polling and retry can launch the device`() = runTest {
        val source = FakeEmulators(offline = true)
        source.startFails = true
        val controller = HubController(source)
        val running = launch { controller.run() }
        runCurrent()
        controller.dispatch(HubAction.Start("Test_AVD"))
        runCurrent()
        controller.dispatch(HubAction.Refresh)
        runCurrent()
        assertEquals(DeviceStatus.ERROR, controller.state.value.devices.single().status)
        assertTrue(controller.state.value.message.contains("SDK"))
        source.startFails = false
        controller.dispatch(HubAction.Start("Test_AVD"))
        runCurrent()
        assertEquals(2, source.startCalls)
        assertEquals(DeviceStatus.STARTING, controller.state.value.devices.single().status)
        running.cancelAndJoin()
    }

    @Test
    fun `close stops selected and unselected emulators exactly once before closing`() = runTest {
        val other = RunningEmulator("emulator-5556", "Pixel Fold")
        val source = FakeEmulators(extra = other)
        val controller = HubController(source)
        val running = launch { controller.run() }
        runCurrent()
        controller.dispatch(HubAction.Close)
        controller.dispatch(HubAction.Close)
        runCurrent()
        running.join()
        assertEquals(setOf(source.device, other), source.stopped.toSet())
        assertEquals(2, source.stopped.size)
        assertTrue(source.session.closed)
        assertTrue(controller.state.value.closed)
    }

    @Test
    fun `failed shutdown keeps window open and permits retry after stopping other instances`() = runTest {
        val other = RunningEmulator("emulator-5556", "Pixel Fold")
        val source = FakeEmulators(extra = other)
        source.failStop = source.device.serial
        val controller = HubController(source)
        val running = launch { controller.run() }
        runCurrent()
        controller.dispatch(HubAction.Close)
        runCurrent()
        assertFalse(controller.state.value.closed)
        assertFalse(controller.state.value.closing)
        assertTrue(controller.state.value.closeError!!.contains(source.device.name))
        assertEquals(listOf(source.device), controller.state.value.emulators)
        assertTrue(source.stopped.contains(other))
        source.failStop = null
        controller.dispatch(HubAction.Close)
        runCurrent()
        running.join()
        assertTrue(controller.state.value.closed)
        assertEquals(1, source.stopped.count { it == other })
    }

    @Test
    fun `window cancellation releases input and closes active session`() = runTest {
        val source = FakeEmulators()
        val controller = HubController(source)
        val running = launch { controller.run() }
        runCurrent()
        assertEquals(source.device, controller.state.value.selected)
        running.cancelAndJoin()
        assertEquals(1, source.session.releaseCount)
        assertTrue(source.session.closed)
    }

    @Test
    fun `input to another serial is ignored`() = runTest {
        val source = FakeEmulators()
        val controller = HubController(source)
        val running = launch { controller.run() }
        runCurrent()
        controller.dispatch(HubAction.Touch("emulator-5556", TouchInput(0.2f, 0.4f, true)))
        controller.dispatch(HubAction.Touch(source.device.serial, TouchInput(0.3f, 0.5f, true)))
        runCurrent()
        assertEquals(listOf(TouchInput(0.3f, 0.5f, true)), source.session.touches)
        running.cancelAndJoin()
    }

    @Test
    fun `stream failure closes session and exposes recoverable state`() = runTest {
        val source = FakeEmulators(fail = true)
        val controller = HubController(source)
        val running = launch { controller.run() }
        runCurrent()
        assertFalse(controller.state.value.connected)
        assertFalse(controller.state.value.connecting)
        assertEquals("Conexión rechazada", controller.state.value.message)
        assertTrue(source.session.closed)
        running.cancelAndJoin()
    }
}

private class FakeEmulators(fail: Boolean = false, private val extra: RunningEmulator? = null, private val offline: Boolean = false) : EmulatorInventory {
    val device = RunningEmulator("emulator-5554", "Test AVD", avdId = "Test_AVD")
    val session = FakeSession(device, fail)
    val stopped = mutableListOf<RunningEmulator>()
    var failStop: String? = null
    var startCalls = 0
    var connectCalls = 0
    var bootReady = false
    var startFails = false
    private var started = false
    private val terminated = mutableSetOf<String>()
    override suspend fun discover() = listOfNotNull(device, extra).filter { it.instanceId !in terminated }
    override suspend fun list(): List<AvailableEmulator> = if (device.avdId in deleted) emptyList() else if (offline) {
        listOf(AvailableEmulator("Test_AVD", device.name, "36.1",
            if (!started) DeviceStatus.OFF else if (bootReady) DeviceStatus.READY else DeviceStatus.STARTING,
            device.takeIf { started }))
    } else discover().map { AvailableEmulator(it.avdId ?: it.serial, it.name, "36.1", DeviceStatus.READY, it) }
    val deleted = mutableListOf<String>()
    var deleteFails = false
    override suspend fun delete(id: String) { if (deleteFails) throw SessionFailure("Deletion failed"); deleted += id }
    override suspend fun start(id: String): RunningEmulator {
        startCalls++
        if (startFails) throw SessionFailure("Falta el SDK de Android.")
        started = true
        terminated -= device.instanceId
        return device
    }
    override suspend fun connect(serial: String): EmulatorSession {
        connectCalls++
        return session
    }
    override suspend fun stop(emulator: RunningEmulator) {
        stopped += emulator
        if (emulator.serial == failStop) throw SessionFailure("No se pudo apagar la instancia.")
        terminated += emulator.instanceId
        started = false
    }
}

private class FakeSession(override val emulator: RunningEmulator, private val fail: Boolean) : EmulatorSession {
    override suspend fun capabilities() = EmulatorCapabilities(navigation = true)
    val commands = mutableListOf<EmulatorCommand>()
    var commandFails = false
    val screen = MutableSharedFlow<ScreenFrame>(extraBufferCapacity = 1)
    override suspend fun execute(command: EmulatorCommand, surface: DisplaySurface) {
        if (commandFails) throw SessionFailure("Acción rechazada")
        commands += command
    }
    var closed = false
    var releaseCount = 0
    val touches = mutableListOf<TouchInput>()
    override val frames: Flow<ScreenFrame> = flow {
        if (fail) throw SessionFailure("Conexión rechazada")
        screen.collect { emit(it) }
    }
    override suspend fun touch(input: TouchInput) { touches += input }
    override suspend fun key(key: String, surface: DisplaySurface?) = Unit
    override suspend fun text(text: String, surface: DisplaySurface?) = Unit
    override suspend fun releaseInput() { releaseCount++ }
    override fun close() { closed = true }
}
