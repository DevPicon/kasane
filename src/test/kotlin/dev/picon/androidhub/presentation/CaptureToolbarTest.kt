package dev.picon.androidhub.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.picon.androidhub.core.*
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CaptureToolbarTest {
    @get:Rule val compose = createComposeRule()
    @Test fun capturesStopAndGeometryControlsRespectRecording() {
        val emulator = RunningEmulator("emulator-5554", "Fold", "A")
        val state = mutableStateOf(CaptureState())
        val actions = mutableListOf<CaptureAction>()
        compose.setContent { MaterialTheme {
            androidx.compose.foundation.layout.Column {
                CaptureToolbar(state.value, emulator, true, actions::add)
                EmulatorActionBar(EmulatorCapabilities(true, true, setOf(FoldPosture.OPEN)), FoldPosture.OPEN, true, {},
                    geometryEnabled = state.value.recording == null)
            }
        } }
        compose.onNodeWithText("Captura PNG").performClick()
        compose.onNodeWithText("Grabar video").performClick()
        assertEquals(listOf(CaptureAction.Screenshot(emulator), CaptureAction.Record(emulator)), actions)
        compose.runOnIdle { state.value = CaptureState(busy = true, recording = emulator) }
        compose.onNodeWithText("Captura PNG").assertIsNotEnabled()
        compose.onNodeWithText("Grabar video").assertDoesNotExist()
        compose.onNodeWithContentDescription("Rotar 90°").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Postura Abierto").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Inicio").assertIsEnabled()
        compose.onNodeWithText("Detener y guardar").performClick()
        assertEquals(CaptureAction.Stop, actions.last())
        compose.runOnIdle { state.value = state.value.copy(stopping = true) }
        compose.onNodeWithText("Detener y guardar").assertIsNotEnabled()
    }
}
