package dev.picon.androidhub.presentation

import dev.picon.androidhub.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SystemImagesControllerTest {
    @Test fun `explicit download runs once publishes progress and refreshes creation`() = runTest {
        val source = ImagesFake()
        val controller = SystemImagesController(source)
        val installed = mutableListOf<String>()
        val job = launch { controller.run(installed::add) }
        controller.dispatch(SystemImagesAction.Open()); runCurrent()
        assertEquals(0, source.calls)
        controller.dispatch(SystemImagesAction.Install)
        controller.dispatch(SystemImagesAction.Install); runCurrent()
        assertEquals(1, source.calls)
        assertEquals(listOf("image"), installed)
        assertTrue(controller.state.value.packages.single().installed)
        assertFalse(controller.state.value.canInstall)
        job.cancelAndJoin()
    }

    @Test fun `failure preserves selection and retry succeeds`() = runTest {
        val source = ImagesFake().apply { fails = true }
        val controller = SystemImagesController(source)
        val job = launch { controller.run {} }
        controller.dispatch(SystemImagesAction.Open()); runCurrent()
        controller.dispatch(SystemImagesAction.Install); runCurrent()
        assertEquals("Network failed", controller.state.value.error)
        assertTrue(controller.state.value.canInstall)
        source.fails = false
        controller.dispatch(SystemImagesAction.Install); runCurrent()
        assertEquals("image", controller.state.value.installedId)
        job.cancelAndJoin()
    }

    @Test fun `shutdown awaits cancellation and late progress is ignored`() = runTest {
        val source = ImagesFake().apply { wait = true }
        val controller = SystemImagesController(source)
        val installed = mutableListOf<String>()
        val job = launch { controller.run(installed::add) }
        controller.dispatch(SystemImagesAction.Open()); runCurrent()
        controller.dispatch(SystemImagesAction.Install); runCurrent()
        assertEquals(20, controller.state.value.progress.percent)
        controller.cancelAndAwait()
        assertTrue(source.cleaned)
        assertFalse(controller.state.value.visible)
        controller.dispatch(SystemImagesAction.Installed(2, "image")); runCurrent()
        assertTrue(installed.isEmpty())
        job.cancelAndJoin()
    }

    @Test fun `profile filters incompatible images and cannot submit while hidden`() = runTest {
        val source = ImagesFake()
        val controller = SystemImagesController(source)
        val job = launch { controller.run {} }
        controller.dispatch(SystemImagesAction.Open(AvdHardwareProfile("tv", "TV", "Generic", FormFactor.TV, requiredTag = "android-tv"))); runCurrent()
        assertTrue(controller.state.value.compatible.isEmpty())
        controller.dispatch(SystemImagesAction.Select("image"))
        controller.dispatch(SystemImagesAction.Install); runCurrent()
        assertEquals(0, source.calls)
        controller.dispatch(SystemImagesAction.Cancel)
        controller.dispatch(SystemImagesAction.Install); runCurrent()
        assertEquals(0, source.calls)
        job.cancelAndJoin()
    }
}

private class ImagesFake : SystemImageRepository {
    var calls = 0
    var fails = false
    var wait = false
    var cleaned = false
    override suspend fun list() = listOf(SystemImagePackage(InstalledSystemImage("image", "Android", 35, "google_apis", "arm64-v8a"), false))
    override suspend fun install(id: String, onProgress: (ImageDownloadProgress) -> Unit) {
        calls++
        try {
            onProgress(ImageDownloadProgress(20, "Downloading"))
            if (wait) awaitCancellation()
            if (fails) throw SessionFailure("Network failed")
        } finally { cleaned = true }
    }
}
