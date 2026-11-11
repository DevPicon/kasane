package dev.picon.androidhub.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.picon.androidhub.core.*
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class AvdDeletionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `options opens delete action and confirmation explains data loss`() {
        val device = AvailableEmulator("Test", "Test AVD", "36", DeviceStatus.OFF)
        val requested = mutableStateOf(false)
        val actions = mutableListOf<HubAction>()
        compose.setContent { MaterialTheme {
            DeviceInventoryRow(device, true, true, {}, { requested.value = true })
            AvdDeletionDialog(device.takeIf { requested.value }, false, null, actions::add)
        } }
        compose.onNodeWithContentDescription("Opciones de Test AVD").performClick()
        compose.onNodeWithText("Eliminar…").performClick()
        compose.runOnIdle { assertEquals(true, requested.value) }
        compose.onNodeWithText("¿Eliminar Test AVD?").assertExists()
        compose.onNodeWithText("Se borrarán permanentemente", substring = true).assertExists()
        compose.onNodeWithText("Cancelar").performClick()
        compose.runOnIdle { assertEquals(listOf<HubAction>(HubAction.CancelDelete), actions) }
    }

    @Test fun `active emulator cannot be deleted from options`() {
        val device = AvailableEmulator("Test", "Test AVD", "36", DeviceStatus.STARTING)
        compose.setContent { MaterialTheme { DeviceInventoryRow(device, true, true, {}, { error("must not delete") }) } }
        compose.onNodeWithContentDescription("Opciones de Test AVD").performClick()
        compose.onNodeWithText("Apaga el emulador para eliminarlo").assertIsNotEnabled()
    }
}
