package dev.picon.androidhub.presentation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.picon.androidhub.core.*
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class SystemImagesContentTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `download is explicit installed cannot submit and busy can cancel`() {
        val entry = SystemImagePackage(InstalledSystemImage("image", "API 35 · Google APIs", 35, "google_apis", "arm64-v8a"), false)
        val state = mutableStateOf(SystemImagesState(visible = true, packages = listOf(entry), selectedId = "image"))
        val actions = mutableListOf<SystemImagesAction>()
        compose.setContent { SystemImagesContent(state.value, actions::add, Modifier.size(760.dp, 660.dp)) }
        compose.onNodeWithText("Descargar imagen").assertIsEnabled().performClick()
        assertEquals(listOf<SystemImagesAction>(SystemImagesAction.Install), actions)
        compose.runOnIdle { state.value = state.value.copy(installing = true, progress = ImageDownloadProgress(25, "Downloading")) }
        compose.onNodeWithText("Descargar imagen").assertIsNotEnabled()
        compose.onNodeWithText("Cancelar descarga").performClick()
        assertEquals(SystemImagesAction.Cancel, actions.last())
        compose.runOnIdle { state.value = state.value.copy(installing = false, packages = listOf(entry.copy(installed = true)), selectedId = null) }
        compose.onNodeWithText("Instalada").assertExists()
        compose.onNodeWithText("Descargar imagen").assertIsNotEnabled()
    }
}
