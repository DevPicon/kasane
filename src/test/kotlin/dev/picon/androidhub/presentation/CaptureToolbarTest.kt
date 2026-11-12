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
        compose.onNodeWithContentDescription("Take screenshot").performClick()
        compose.onNodeWithContentDescription("Record video").performClick()
        assertEquals(listOf(CaptureAction.Screenshot(emulator), CaptureAction.Record(emulator)), actions)
        compose.runOnIdle { state.value = CaptureState(busy = true, recording = emulator) }
        compose.onNodeWithContentDescription("Take screenshot").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Record video").assertDoesNotExist()
        compose.onNodeWithContentDescription("Rotate 90°").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Posture: Open").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Home").assertIsEnabled()
        compose.onNodeWithContentDescription("Stop recording").performClick()
        assertEquals(CaptureAction.Stop, actions.last())
        compose.runOnIdle { state.value = state.value.copy(stopping = true) }
        compose.onNodeWithContentDescription("Stop recording").assertIsNotEnabled()
    }
    @Test fun pendingRecordingCanBeSavedOrDiscardedOnlyAfterConfirmation() {
        val actions = mutableListOf<CaptureAction>()
        compose.setContent { MaterialTheme { CaptureToolbar(CaptureState(pendingVideo = "/owned/video.mp4"), null, false, actions::add) } }
        compose.onNodeWithContentDescription("Save recording").performClick()
        assertEquals(listOf<CaptureAction>(CaptureAction.Save), actions)
        compose.onNodeWithContentDescription("Discard recording").performClick()
        assertEquals(listOf<CaptureAction>(CaptureAction.Save), actions)
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Discard recording").performClick()
        compose.onNodeWithText("Discard").performClick()
        assertEquals(listOf<CaptureAction>(CaptureAction.Save, CaptureAction.Discard), actions)
    }

}
