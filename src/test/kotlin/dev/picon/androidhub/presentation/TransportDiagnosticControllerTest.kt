package dev.picon.androidhub.presentation

import dev.picon.androidhub.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TransportDiagnosticControllerTest {
    @Test fun `successful report keeps active screen session and reconnect is explicit`() = runTest {
        val source = DiagnosticInventory()
        val controller = HubController(source, DiagnosticFake())
        val job = launch { controller.run() }; runCurrent()
        controller.dispatch(HubAction.Diagnose("A")); runCurrent()
        assertTrue(controller.state.value.diagnostic!!.report!!.healthy)
        assertEquals(1, source.sessions.size)
        assertFalse(source.sessions.first().closed)
        controller.dispatch(HubAction.Reconnect); runCurrent()
        assertEquals(2, source.sessions.size)
        assertTrue(source.sessions.first().closed)
        assertNull(controller.state.value.diagnostic)
        job.cancelAndJoin()
    }

    @Test fun `changing selection cancels probe and ignores its final late check`() = runTest {
        val source = DiagnosticInventory()
        val inspector = DiagnosticFake(wait = true)
        val controller = HubController(source, inspector)
        val job = launch { controller.run() }; runCurrent()
        controller.dispatch(HubAction.Diagnose("A")); runCurrent()
        assertTrue(controller.state.value.diagnostic!!.checking)
        controller.dispatch(HubAction.SelectDevice("B")); runCurrent()
        assertTrue(inspector.cleaned)
        assertNull(controller.state.value.diagnostic)
        assertEquals("B", controller.state.value.selectedDeviceId)
        job.cancelAndJoin()
    }

    @Test fun `close awaits probe cleanup and failures never expose exception text`() = runTest {
        val source = DiagnosticInventory()
        val inspector = DiagnosticFake(wait = true)
        val controller = HubController(source, inspector)
        val job = launch { controller.run() }; runCurrent()
        controller.dispatch(HubAction.Diagnose("A")); runCurrent()
        controller.dispatch(HubAction.Close); runCurrent()
        assertTrue(inspector.cleaned)
        assertTrue(controller.state.value.closed)
        job.join()

        val failed = HubController(DiagnosticInventory(), object : TransportDiagnostics {
            override suspend fun inspect(device: AvailableEmulator, onCheck: (DiagnosticCheck) -> Unit): TransportReport = error("Bearer private-secret")
        })
        val second = launch { failed.run() }; runCurrent()
        failed.dispatch(HubAction.Diagnose("A")); runCurrent()
        assertFalse(failed.state.value.diagnostic!!.checking)
        assertFalse(failed.state.value.diagnostic.toString().contains("private-secret"))
        second.cancelAndJoin()
    }

    @Test fun `replacement and empty stream cannot be mistaken for the selected connection`() = runTest {
        val source = DiagnosticInventory()
        val controller = HubController(source, DiagnosticFake())
        val job = launch { controller.run() }; runCurrent()
        controller.dispatch(HubAction.Diagnose("A")); runCurrent()
        source.active["A"] = source.active.getValue("A").copy(instanceId = "replacement")
        controller.dispatch(HubAction.Reconnect); runCurrent()
        assertNull(controller.state.value.diagnostic)
        assertTrue(source.sessions.size <= 2)
        job.cancelAndJoin()

        val emptySource = DiagnosticInventory(emptyStream = true)
        val empty = HubController(emptySource, DiagnosticFake())
        val emptyJob = launch { empty.run() }; runCurrent()
        assertFalse(empty.state.value.connecting)
        assertTrue(empty.state.value.message.contains("stream de pantalla terminó"))
        assertTrue(emptySource.sessions.first().closed)
        emptyJob.cancelAndJoin()
    }

    @Test fun `first frame deadline makes a silent connection recoverable`() = runTest {
        val controller = HubController(DiagnosticInventory(), DiagnosticFake())
        val job = launch { controller.run() }; runCurrent()
        assertTrue(controller.state.value.connecting)
        advanceTimeBy(12_001); runCurrent()
        assertFalse(controller.state.value.connecting)
        assertFalse(controller.state.value.connected)
        assertTrue(controller.state.value.message.contains("primer frame"))
        job.cancelAndJoin()
    }
}

private class DiagnosticFake(private val wait: Boolean = false) : TransportDiagnostics {
    var cleaned = false
    override suspend fun inspect(device: AvailableEmulator, onCheck: (DiagnosticCheck) -> Unit): TransportReport {
        val checks = DiagnosticStage.entries.map { DiagnosticCheck(it, DiagnosticOutcome.PASS, "OK", 1) }
        try {
            onCheck(checks.first())
            if (wait) awaitCancellation()
            return TransportReport(checks, suggestion = "Reconnect if needed")
        } finally { cleaned = true; onCheck(checks.last()) }
    }
}

private class DiagnosticInventory(private val emptyStream: Boolean = false) : EmulatorInventory {
    val active = mutableMapOf("A" to RunningEmulator("emulator-5554", "A", "A:1", "A"), "B" to RunningEmulator("emulator-5556", "B", "B:1", "B"))
    val sessions = mutableListOf<DiagnosticSession>()
    override suspend fun list() = listOf("A", "B").map { AvailableEmulator(it, it, "36", if (it in active) DeviceStatus.READY else DeviceStatus.OFF, active[it]) }
    override suspend fun discover() = active.values.toList()
    override suspend fun connect(serial: String) = DiagnosticSession(active.values.single { it.serial == serial }, emptyStream).also { sessions += it }
    override suspend fun stop(emulator: RunningEmulator) { active.remove(emulator.avdId) }
    override suspend fun start(id: String): RunningEmulator = error("No start during diagnostic")
    override suspend fun delete(id: String) = error("No delete during diagnostic")
}

private class DiagnosticSession(override val emulator: RunningEmulator, emptyStream: Boolean) : EmulatorSession {
    var closed = false
    override val frames: Flow<ScreenFrame> = if (emptyStream) emptyFlow() else flow { awaitCancellation() }
    override suspend fun capabilities() = EmulatorCapabilities()
    override suspend fun execute(command: EmulatorCommand, surface: DisplaySurface) = Unit
    override suspend fun touch(input: TouchInput) = Unit
    override suspend fun key(key: String, surface: DisplaySurface?) = Unit
    override suspend fun text(text: String, surface: DisplaySurface?) = Unit
    override suspend fun releaseInput() = Unit
    override fun close() { closed = true }
}
