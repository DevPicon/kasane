package dev.picon.androidhub.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.picon.androidhub.core.*
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class DiagnosticDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `progress can cancel completed report can retry or reconnect explicitly`() {
        val state = mutableStateOf(DiagnosticUiState("A", "Phone", "instance"))
        val actions = mutableListOf<HubAction>()
        compose.setContent { MaterialTheme { DiagnosticDialog(state.value, true, actions::add) } }
        compose.onNodeWithText("Diagnóstico del transporte").assertExists()
        compose.onNodeWithText("Comprobar de nuevo").assertIsNotEnabled()
        compose.onNodeWithText("Cancelar").performClick()
        assertEquals(HubAction.CancelDiagnosis, actions.last())
        val checks = DiagnosticStage.entries.map { DiagnosticCheck(it, DiagnosticOutcome.PASS, "OK") }
        compose.runOnIdle { state.value = state.value.copy(checking = false, checks = checks, report = TransportReport(checks, suggestion = "Everything ready")) }
        compose.onNodeWithText("Transporte disponible").assertExists()
        compose.onNodeWithText("Reconectar pantalla").performScrollTo().assertIsEnabled().performClick()
        assertEquals(HubAction.Reconnect, actions.last())
        compose.onNodeWithText("Comprobar de nuevo").assertIsEnabled().performClick()
        assertEquals(HubAction.Diagnose("A"), actions.last())
    }
}
