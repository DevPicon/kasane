package dev.picon.androidhub.presentation

import dev.picon.androidhub.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class EmulatorPowerTest {
    @Test fun `stop another device preserves selection session and its process`() = runTest {
        val source = PowerInventory()
        val controller = HubController(source)
        val job = launch { controller.run() }; runCurrent()
        val session = source.sessions.single()
        controller.dispatch(HubAction.Stop("B")); runCurrent()
        assertEquals("A", controller.state.value.selectedDeviceId)
        assertFalse(session.closed)
        assertNotNull(source.active["A"])
        assertNull(source.active["B"])
        assertEquals(DeviceStatus.OFF, controller.state.value.devices.single { it.id == "B" }.status)
        job.cancelAndJoin()
    }

    @Test fun `restart retains data and selection uses new identity and ignores old failure`() = runTest {
        val source = PowerInventory()
        val controller = HubController(source)
        val job = launch { controller.run() }; runCurrent()
        val old = source.active.getValue("A")
        controller.dispatch(HubAction.Restart("A")); runCurrent()
        assertEquals(listOf("A"), source.started)
        assertNotEquals(old.instanceId, source.active.getValue("A").instanceId)
        assertEquals("A", controller.state.value.selectedDeviceId)
        assertTrue(source.sessions.first().closed)
        assertEquals(source.active.getValue("A").instanceId, controller.state.value.selected?.instanceId)
        val newSession = source.sessions.last()
        controller.dispatch(HubAction.Failed(old.instanceId, "stale")); runCurrent()
        assertFalse(newSession.closed)
        assertNotEquals("stale", controller.state.value.message)
        job.cancelAndJoin()
    }

    @Test fun `duplicate power actions do not launch work twice and selection stays responsive`() = runTest {
        val source = PowerInventory().apply { gate = CompletableDeferred() }
        val controller = HubController(source)
        val job = launch { controller.run() }; runCurrent()
        controller.dispatch(HubAction.Restart("A"))
        controller.dispatch(HubAction.Restart("A"))
        controller.dispatch(HubAction.Stop("A")); runCurrent()
        assertEquals(DeviceStatus.RESTARTING, controller.state.value.devices.single { it.id == "A" }.status)
        assertEquals(1, source.stopCalls.size)
        controller.dispatch(HubAction.SelectDevice("B")); runCurrent()
        assertEquals("B", controller.state.value.selectedDeviceId)
        source.gate!!.complete(Unit); runCurrent()
        assertEquals(listOf("A"), source.started)
        assertEquals("B", controller.state.value.selectedDeviceId)
        job.cancelAndJoin()
    }

    @Test fun `stop failure does not restart and error remains visible after inventory refresh`() = runTest {
        val source = PowerInventory().apply { failStop = true }
        val controller = HubController(source)
        val job = launch { controller.run() }; runCurrent()
        controller.dispatch(HubAction.Restart("A")); runCurrent()
        assertTrue(source.started.isEmpty())
        assertEquals("Stop failed", controller.state.value.powerErrors["A"])
        assertNotNull(source.active["A"])
        source.failStop = false
        controller.dispatch(HubAction.Stop("A")); runCurrent()
        assertFalse("A" in controller.state.value.powerErrors)
        assertNull(source.active["A"])
        job.cancelAndJoin()
    }

    @Test fun `close during restart waits cancellation stops remaining and never starts again`() = runTest {
        val source = PowerInventory().apply { gate = CompletableDeferred() }
        val controller = HubController(source)
        val job = launch { controller.run() }; runCurrent()
        controller.dispatch(HubAction.Restart("A")); runCurrent()
        controller.dispatch(HubAction.Close); runCurrent()
        assertTrue(controller.state.value.closed)
        assertTrue(source.active.isEmpty())
        assertTrue(source.started.isEmpty())
        assertTrue(source.cancelledStop)
        job.join()
    }

    @Test fun `a replacement instance is never stopped through an old request`() = runTest {
        val source = PowerInventory()
        val controller = HubController(source)
        val job = launch { controller.run() }; runCurrent()
        source.active["A"] = source.active.getValue("A").copy(instanceId = "replacement")
        controller.dispatch(HubAction.Restart("A")); runCurrent()
        assertTrue(source.stopCalls.isEmpty())
        assertTrue(source.started.isEmpty())
        assertEquals("replacement", source.active.getValue("A").instanceId)
        assertTrue(controller.state.value.powerErrors.containsKey("A"))
        job.cancelAndJoin()
    }
    @Test fun `stop and close await recording finalization before powering off`() = runTest {
        val source = PowerInventory()
        val finalization = CompletableDeferred<Unit>()
        var hookTarget: RunningEmulator? = null
        val controller = HubController(source, beforePowerOff = { hookTarget = it; finalization.await() })
        val job = launch { controller.run() }; runCurrent()
        controller.dispatch(HubAction.Stop("B")); runCurrent()
        assertEquals("B", hookTarget?.avdId)
        assertTrue(source.stopCalls.isEmpty())
        finalization.complete(Unit); runCurrent()
        assertEquals(listOf("B:1"), source.stopCalls)
        job.cancelAndJoin()

        val shutdownSource = PowerInventory()
        val closeGate = CompletableDeferred<Unit>()
        val closing = HubController(shutdownSource, beforeClose = { closeGate.await() })
        val closeJob = launch { closing.run() }; runCurrent()
        closing.dispatch(HubAction.Close); runCurrent()
        assertTrue(shutdownSource.stopCalls.isEmpty())
        closeGate.complete(Unit); runCurrent()
        assertTrue(closing.state.value.closed)
        closeJob.join()
    }
    @Test fun `pending export blocks close without disconnecting the live session`() = runTest {
        val source = PowerInventory()
        var pending = true
        val controller = HubController(source, beforeClose = { if (pending) throw PendingCaptureExport("Save pending video") })
        val runner = launch { controller.run() }; runCurrent()
        val session = source.sessions.single()
        controller.dispatch(HubAction.Close); runCurrent()
        assertFalse(controller.state.value.closing)
        assertFalse(controller.state.value.closed)
        assertFalse(session.closed)
        assertEquals("Save pending video", controller.state.value.message)
        assertTrue(source.stopCalls.isEmpty())
        pending = false
        controller.dispatch(HubAction.Close); runCurrent()
        assertTrue(controller.state.value.closed)
        assertTrue(session.closed)
        runner.join()
    }

}

private class PowerInventory : EmulatorInventory {
    val active = mutableMapOf("A" to RunningEmulator("emulator-5554", "A", "A:1", "A"), "B" to RunningEmulator("emulator-5556", "B", "B:1", "B"))
    val sessions = mutableListOf<PowerSession>()
    val started = mutableListOf<String>()
    val stopCalls = mutableListOf<String>()
    var failStop = false
    var gate: CompletableDeferred<Unit>? = null
    var cancelledStop = false
    private var version = 1
    override suspend fun discover() = active.values.toList()
    override suspend fun list() = listOf("A", "B").map { id -> AvailableEmulator(id, id, "36", if (id in active) DeviceStatus.READY else DeviceStatus.OFF, active[id]) }
    override suspend fun start(id: String): RunningEmulator {
        started += id
        return RunningEmulator(if (id == "A") "emulator-5554" else "emulator-5556", id, "$id:${++version}", id).also { active[id] = it }
    }
    override suspend fun stop(emulator: RunningEmulator) {
        stopCalls += emulator.instanceId
        if (failStop) throw SessionFailure("Stop failed")
        if (stopCalls.size == 1) try { gate?.await() } catch (error: CancellationException) { cancelledStop = true; throw error }
        if (active[emulator.avdId]?.instanceId == emulator.instanceId) active.remove(emulator.avdId)
    }
    override suspend fun connect(serial: String): EmulatorSession = PowerSession(active.values.single { it.serial == serial }).also { sessions += it }
    override suspend fun delete(id: String) = error("Not part of these tests")
}

private class PowerSession(override val emulator: RunningEmulator) : EmulatorSession {
    var closed = false
    override val frames: Flow<ScreenFrame> = flow { awaitCancellation() }
    override suspend fun capabilities() = EmulatorCapabilities()
    override suspend fun execute(command: EmulatorCommand, surface: DisplaySurface) = Unit
    override suspend fun touch(input: TouchInput) = Unit
    override suspend fun key(key: String, surface: DisplaySurface?) = Unit
    override suspend fun text(text: String, surface: DisplaySurface?) = Unit
    override suspend fun releaseInput() = Unit
    override fun close() { closed = true }
}
