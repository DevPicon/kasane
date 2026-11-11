package dev.picon.androidhub.presentation

import dev.picon.androidhub.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AvdCreationControllerTest {
    @Test
    fun `refresh after downloading selects image and preserves edited name and resources`() = runTest {
        val source = CreationFake()
        val controller = AvdCreationController(source)
        val job = launch { controller.run {} }
        controller.dispatch(AvdCreationAction.Open); runCurrent()
        controller.dispatch(AvdCreationAction.Name("Mine"))
        controller.dispatch(AvdCreationAction.Ram("3072"))
        controller.dispatch(AvdCreationAction.Storage("6")); runCurrent()
        source.available = source.available.copy(images = source.available.images + InstalledSystemImage("new", "API 35", 35, "google_apis", "arm64-v8a"))
        controller.dispatch(AvdCreationAction.RefreshImages("new")); runCurrent()
        assertEquals("new", controller.state.value.imageId)
        assertEquals("Mine", controller.state.value.name)
        assertEquals("3072", controller.state.value.ram)
        assertEquals("6", controller.state.value.storage)
        assertTrue(controller.state.value.canCreate)
        job.cancelAndJoin()
    }

    @Test
    fun `suggested name avoids duplicates and a user name survives profile changes`() = runTest {
        val source = CreationFake()
        val catalog = source.available.copy(existingNames = setOf("phone_API_36"),
            profiles = source.available.profiles + AvdHardwareProfile("tablet", "Tablet", "Generic", FormFactor.TABLET))
        val creator = object : AvdCreator by source { override suspend fun catalog() = catalog }
        val controller = AvdCreationController(creator)
        val running = launch { controller.run {} }
        controller.dispatch(AvdCreationAction.Open); runCurrent()
        assertEquals("phone_API_36_1", controller.state.value.name)
        assertTrue(controller.state.value.canCreate)
        controller.dispatch(AvdCreationAction.Name("Chosen_Name"))
        controller.dispatch(AvdCreationAction.Profile("tablet")); runCurrent()
        assertEquals("Chosen_Name", controller.state.value.name)
        running.cancelAndJoin()
    }

    @Test
    fun `creation error keeps the form open and allows retry`() = runTest {
        val source = CreationFake().apply { fails = true }
        val controller = AvdCreationController(source)
        val created = mutableListOf<String>()
        val running = launch { controller.run(created::add) }
        controller.dispatch(AvdCreationAction.Open); runCurrent()
        controller.dispatch(AvdCreationAction.Name("New_AVD")); controller.dispatch(AvdCreationAction.Submit); runCurrent()
        assertTrue(controller.state.value.visible)
        assertFalse(controller.state.value.creating)
        assertEquals("Fallo del CLI", controller.state.value.error)
        source.fails = false
        controller.dispatch(AvdCreationAction.Submit); runCurrent()
        assertEquals(listOf("New_AVD"), created)
        running.cancelAndJoin()
    }

    @Test
    fun `valid form creates once and selects the new device through callback`() = runTest {
        val source = CreationFake()
        val controller = AvdCreationController(source)
        val created = mutableListOf<String>()
        val running = launch { controller.run(created::add) }
        controller.dispatch(AvdCreationAction.Open); runCurrent()
        assertTrue(controller.state.value.canCreate)
        assertEquals("phone_API_36", controller.state.value.name)
        controller.dispatch(AvdCreationAction.Name("New_AVD")); runCurrent()
        assertTrue(controller.state.value.canCreate)
        controller.dispatch(AvdCreationAction.Submit); controller.dispatch(AvdCreationAction.Submit); runCurrent()
        assertEquals(1, source.calls)
        assertEquals(listOf("New_AVD"), created)
        assertFalse(controller.state.value.visible)
        running.cancelAndJoin()
    }

    @Test
    fun `shutdown waits for cancellation and discarded creation never notifies success`() = runTest {
        val source = CreationFake(wait = true)
        val controller = AvdCreationController(source)
        val created = mutableListOf<String>()
        val running = launch { controller.run(created::add) }
        controller.dispatch(AvdCreationAction.Open); runCurrent()
        controller.dispatch(AvdCreationAction.Name("New_AVD")); controller.dispatch(AvdCreationAction.Submit); runCurrent()
        assertTrue(controller.state.value.creating)
        controller.cancelAndAwait()
        assertTrue(source.cleaned)
        assertFalse(controller.state.value.visible)
        assertTrue(created.isEmpty())
        controller.dispatch(AvdCreationAction.Submit); runCurrent()
        assertEquals(1, source.calls)
        running.cancelAndJoin()
    }

    @Test
    fun `invalid resources duplicate name and incompatible image prevent submission`() {
        val catalog = CreationFake().available
        val request = AvdCreationRequest("New_AVD", "phone", "img", 2048, 4)
        assertNull(request.validationError(catalog))
        assertNotNull(request.copy(name = "../bad").validationError(catalog))
        assertNotNull(request.copy(name = "existing").validationError(catalog))
        assertNotNull(request.copy(ramMb = 0).validationError(catalog))
        assertNotNull(request.copy(storageGb = 100).validationError(catalog))
        assertNotNull(request.copy(imageId = "removed").validationError(catalog))
        assertFalse(catalog.profiles.single().supports(InstalledSystemImage("tv", "TV", 36, "android-tv", "arm64-v8a")))
    }
}

private class CreationFake(val wait: Boolean = false) : AvdCreator {
    var calls = 0
    var cleaned = false
    var fails = false
    var available = AvdCreationCatalog(listOf(AvdHardwareProfile("phone", "Phone", "Generic", FormFactor.HANDSET)),
        listOf(InstalledSystemImage("img", "Android", 36, "google_apis_playstore", "arm64-v8a")), setOf("Existing"))
    override suspend fun catalog() = available
    override suspend fun create(request: AvdCreationRequest): String {
        calls++
        try { if (fails) throw SessionFailure("Fallo del CLI"); if (wait) awaitCancellation(); return request.name }
        finally { cleaned = true }
    }
}
