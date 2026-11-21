package dev.picon.androidhub.presentation

import dev.picon.androidhub.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureControllerTest {
    private val a = RunningEmulator("emulator-5554", "Fold", "A")
    private val b = RunningEmulator("emulator-5556", "Phone", "B")

    @Test fun `cancel destination has no side effects and screenshot reports success only after save`() = runTest {
        val backend = CaptureFake()
        var path: String? = null
        val controller = CaptureController(backend, CaptureDestination { path }, backend.files)
        val runner = launch { controller.run() }
        controller.dispatch(CaptureAction.Screenshot(a)); runCurrent()
        assertTrue(backend.targets.isEmpty())
        assertFalse(controller.state.value.busy)
        path = "/capture.png"
        controller.dispatch(CaptureAction.Screenshot(b)); runCurrent()
        assertEquals(listOf(b), backend.targets)
        assertEquals(path, controller.state.value.savedPath)
        runner.cancelAndJoin()
    }

    @Test fun `one recording is pinned to original instance and finish unrelated target leaves it running`() = runTest {
        val backend = CaptureFake()
        val controller = CaptureController(backend, CaptureDestination { "/video.mp4" }, backend.files)
        val runner = launch { controller.run() }
        controller.dispatch(CaptureAction.ShowTaps(false))
        controller.dispatch(CaptureAction.Record(a)); runCurrent()
        controller.dispatch(CaptureAction.Record(b)); runCurrent()
        controller.finishAndAwait(b)
        assertEquals(a, controller.state.value.recording)
        assertEquals(listOf(a), backend.targets)
        assertFalse(backend.taps)
        assertNull(controller.state.value.savedPath)
        controller.finishAndAwait(a)
        assertTrue(backend.finished)
        assertNull(controller.state.value.recording)
        assertEquals("/video.mp4", controller.state.value.savedPath)
        runner.cancelAndJoin()
    }

    @Test fun `stop failure remains visible and retry clears it and disposal releases recording`() = runTest {
        val backend = CaptureFake().apply { fail = true }
        val controller = CaptureController(backend, CaptureDestination { "/video.mp4" }, backend.files)
        val runner = launch { controller.run() }
        controller.dispatch(CaptureAction.Record(a)); runCurrent()
        assertEquals("Recording failed", controller.state.value.error)
        assertNull(controller.state.value.savedPath)
        backend.fail = false
        controller.dispatch(CaptureAction.Record(a)); runCurrent()
        assertNull(controller.state.value.error)
        controller.dispatch(CaptureAction.Stop); runCurrent()
        assertTrue(backend.finished)
        assertFalse(controller.state.value.busy)
        backend.finished = false
        controller.dispatch(CaptureAction.Record(a)); runCurrent()
        runner.cancelAndJoin()
        assertTrue(backend.finished)
    }

    @Test fun `shutdown waits recorder finalization rather than signalling emulator shutdown immediately`() = runTest {
        val backend = CaptureFake().apply { finalizing = CompletableDeferred() }
        val controller = CaptureController(backend, CaptureDestination { "/video.mp4" }, backend.files)
        val runner = launch { controller.run() }
        controller.dispatch(CaptureAction.Record(a)); runCurrent()
        val shutdown = launch { controller.finishAndAwait() }; runCurrent()
        assertFalse(shutdown.isCompleted)
        assertTrue(controller.state.value.stopping)
        backend.finalizing!!.complete(Unit); runCurrent()
        assertTrue(shutdown.isCompleted)
        assertTrue(backend.finished)
        runner.cancelAndJoin()
    }
    @Test fun `record starts without a destination dialog and cancelled save remains pending until explicit resolution`() = runTest {
        val backend = CaptureFake()
        var choices = 0
        var path: String? = null
        val controller = CaptureController(backend, CaptureDestination { choices++; path }, backend.files)
        val runner = launch { controller.run() }
        controller.dispatch(CaptureAction.Record(a)); runCurrent()
        assertEquals(0, choices)
        assertEquals(a, controller.state.value.recording)
        controller.dispatch(CaptureAction.Stop); runCurrent()
        assertEquals(1, choices)
        assertNotNull(controller.state.value.pendingVideo)
        assertFalse(controller.state.value.busy)
        assertFailsWith<PendingCaptureExport> { controller.finishAndAwait() }
        controller.dispatch(CaptureAction.Record(b)); runCurrent()
        assertEquals(listOf(a), backend.targets)
        backend.files.failSave = true
        path = "/video.mp4"
        controller.dispatch(CaptureAction.Save); runCurrent()
        assertNotNull(controller.state.value.pendingVideo)
        assertNotNull(controller.state.value.error)
        backend.files.failSave = false
        controller.dispatch(CaptureAction.Save); runCurrent()
        assertNull(controller.state.value.pendingVideo)
        assertEquals(path, controller.state.value.savedPath)
        controller.finishAndAwait()
        runner.cancelAndJoin()
    }

    @Test fun `discard is explicit and removes only the owned pending video`() = runTest {
        val backend = CaptureFake()
        val controller = CaptureController(backend, CaptureDestination { null }, backend.files)
        val runner = launch { controller.run() }
        controller.dispatch(CaptureAction.Record(a)); runCurrent()
        controller.dispatch(CaptureAction.Stop); runCurrent()
        assertEquals(1, backend.files.present.size)
        controller.dispatch(CaptureAction.Discard); runCurrent()
        assertNull(controller.state.value.pendingVideo)
        assertTrue(backend.files.present.isEmpty())
        controller.finishAndAwait()
        runner.cancelAndJoin()
    }

}

private class CaptureFake : EmulatorCapture {
    val files = CaptureFilesFake()
    val targets = mutableListOf<RunningEmulator>()
    var taps = true
    var finished = false
    var fail = false
    var finalizing: CompletableDeferred<Unit>? = null
    override suspend fun screenshot(emulator: RunningEmulator, destination: String) { targets += emulator }
    override suspend fun record(emulator: RunningEmulator, destination: String, showTaps: Boolean,
                                stop: Deferred<Unit>, onStarted: () -> Unit) {
        targets += emulator; taps = showTaps
        if (fail) throw SessionFailure("Recording failed")
        try { onStarted(); stop.await(); finalizing?.await(); files.present += destination }
        finally { finished = true }
    }
}

private class CaptureFilesFake : CaptureFiles {
    val present = mutableSetOf<String>()
    var failSave = false
    private var next = 0
    override suspend fun createVideo() = "/temporary/video-${++next}.mp4"
    override suspend fun exists(path: String) = path in present
    override suspend fun save(path: String, destination: String) {
        if (failSave) throw SessionFailure("Save failed")
        check(present.remove(path))
    }
    override suspend fun discard(path: String) { present.remove(path) }
}
