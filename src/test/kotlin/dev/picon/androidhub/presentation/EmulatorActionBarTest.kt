package dev.picon.androidhub.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.picon.androidhub.core.EmulatorCapabilities
import dev.picon.androidhub.core.EmulatorCommand
import dev.picon.androidhub.core.FoldPosture
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class EmulatorActionBarTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun capabilitiesReplaceControlsAndTransitionDisablesInput() {
        val capabilities = mutableStateOf(EmulatorCapabilities(true, true, setOf(FoldPosture.FOLDED, FoldPosture.OPEN)))
        val enabled = mutableStateOf(true)
        val commands = mutableListOf<EmulatorCommand>()
        compose.setContent {
            MaterialTheme { EmulatorActionBar(capabilities.value, FoldPosture.OPEN, enabled.value, commands::add) }
        }
        compose.onNodeWithContentDescription("Postura Abierto").assertIsSelected()
        compose.onNodeWithContentDescription("Postura Plegado").performClick()
        assertEquals(listOf<EmulatorCommand>(EmulatorCommand.SetPosture(FoldPosture.FOLDED)), commands)
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithContentDescription("Inicio").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Postura Plegado").assertIsNotEnabled()
        compose.runOnIdle { capabilities.value = EmulatorCapabilities(navigation = true) }
        compose.onNodeWithContentDescription("Postura Plegado").assertDoesNotExist()
        compose.onNodeWithContentDescription("Rotar 90°").assertDoesNotExist()
        compose.onNodeWithContentDescription("Inicio").assertExists()
    }

    @Test
    fun keyboardMovesFocusAndActivatesTheNextControl() {
        val commands = mutableListOf<EmulatorCommand>()
        compose.setContent {
            MaterialTheme { EmulatorActionBar(EmulatorCapabilities(navigation = true), null, true, commands::add) }
        }
        compose.onNodeWithContentDescription("Inicio").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithContentDescription("Inicio").assertIsFocused()
        compose.onNodeWithContentDescription("Inicio").performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithContentDescription("Recientes").assertIsFocused().performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(listOf<EmulatorCommand>(EmulatorCommand.Recents), commands)
    }
}
