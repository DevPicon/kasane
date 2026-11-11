package dev.picon.androidhub.presentation

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.picon.androidhub.core.*
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class AvdCreationContentTest {
    @get:Rule val compose = createComposeRule()
    private val catalog = AvdCreationCatalog(listOf(AvdHardwareProfile("phone", "Phone", "Generic", FormFactor.HANDSET)),
        listOf(InstalledSystemImage("img", "API 36.1 · Google Play", 36, "google_apis_playstore", "arm64-v8a")), setOf("Existing"))

    @Test
    fun validationAndCreatingStateControlSubmission() {
        val state = mutableStateOf(AvdCreationState(visible = true, catalog = catalog, profileId = "phone", imageId = "img"))
        val actions = mutableListOf<AvdCreationAction>()
        compose.setContent { AvdCreationContent(state.value, actions::add, Modifier.size(620.dp, 660.dp)) }
        compose.onAllNodesWithText("Crear emulador").assertCountEquals(2)
        compose.onAllNodesWithText("Crear emulador").filter(hasClickAction()).onFirst().assertIsNotEnabled()
        compose.onNodeWithText("Nombre del AVD").performTextInput("Typed_AVD")
        assertEquals(listOf<AvdCreationAction>(AvdCreationAction.Name("Typed_AVD")), actions)
        actions.clear()
        compose.runOnIdle { state.value = state.value.copy(name = "Existing") }
        compose.onNodeWithText("Ya existe un AVD con ese nombre.").assertExists()
        compose.runOnIdle { state.value = state.value.copy(name = "New_AVD") }
        compose.onAllNodesWithText("Crear emulador").filter(hasClickAction()).onFirst().assertIsEnabled().performClick()
        assertEquals(listOf<AvdCreationAction>(AvdCreationAction.Submit), actions)
        compose.runOnIdle { state.value = state.value.copy(creating = true) }
        compose.onNodeWithText("Creando el AVD…").assertExists()
        compose.onAllNodesWithText("Crear emulador").filter(hasClickAction()).onFirst().assertIsNotEnabled()
        compose.onNodeWithText("Cancelar").assertIsEnabled()
    }

    @Test
    fun emptyInstalledImagesOffersExplicitDownload() {
        compose.setContent { AvdCreationContent(AvdCreationState(visible = true, catalog = catalog.copy(images = emptyList(), profiles = emptyList())), {}, Modifier.size(620.dp, 660.dp)) }
        compose.onNodeWithText("No hay imágenes Android instaladas compatibles con este equipo. Usa Descargar imágenes para instalar una.").assertExists()
        compose.onNodeWithText("Nombre del AVD").assertDoesNotExist()
        compose.onNodeWithText("Descargar imágenes…").assertIsEnabled()
    }
}
