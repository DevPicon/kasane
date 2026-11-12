package dev.picon.androidhub.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.picon.androidhub.core.*

data class DiagnosticUiState(
    val deviceId: String,
    val deviceName: String,
    val instanceId: String?,
    val checking: Boolean = true,
    val checks: List<DiagnosticCheck> = emptyList(),
    val report: TransportReport? = null,
    val error: String? = null,
)

@Composable
internal fun DiagnosticDialog(state: DiagnosticUiState?, reconnectEnabled: Boolean, onAction: (HubAction) -> Unit) {
    if (state == null) return
    AlertDialog(modifier = Modifier.width(620.dp), onDismissRequest = { onAction(HubAction.CancelDiagnosis) },
        title = { UiText("Diagnóstico del transporte") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.deviceName, fontWeight = FontWeight.SemiBold)
                UiText("Solo se comprueba conectividad. No se guardan frames, tokens ni datos de las aplicaciones.", fontSize = 12.sp)
                if (state.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                DiagnosticStage.entries.forEach { stage ->
                    val check = state.checks.firstOrNull { it.stage == stage }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val mark = when (check?.outcome) { DiagnosticOutcome.PASS -> "✓"; DiagnosticOutcome.FAIL -> "!"; else -> "—" }
                        val color = when (check?.outcome) { DiagnosticOutcome.PASS -> Color(0xFF355D27); DiagnosticOutcome.FAIL -> MaterialTheme.colorScheme.error; else -> Color(0xFF5C626B) }
                        UiText("$mark ${stage.label()}", color = color, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        UiText(check?.let { "${LocalUiStrings.current.text(it.detail)} · ${if (it.durationMs == 0L) "<1" else it.durationMs} ms" }
                            ?: if (state.checking) "Pendiente…" else "No comprobado.", fontSize = 12.sp)
                    }
                }
                state.report?.let {
                    HorizontalDivider()
                    UiText(if (it.healthy) "Transporte disponible" else "Revisa las comprobaciones indicadas", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    UiText(it.suggestion, fontSize = 12.sp)
                }
                state.error?.let { UiText(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                TextButton(enabled = !state.checking && reconnectEnabled, onClick = { onAction(HubAction.Reconnect) }) { UiText("Reconectar pantalla") }
            }
        },
        dismissButton = { TextButton(onClick = { onAction(HubAction.CancelDiagnosis) }) { UiText(if (state.checking) "Cancelar" else "Cerrar") } },
        confirmButton = { TextButton(enabled = !state.checking, onClick = { onAction(HubAction.Diagnose(state.deviceId)) }) { UiText("Comprobar de nuevo") } })
}

internal fun DiagnosticStage.label(): String = when (this) {
    DiagnosticStage.PROCESS -> "Proceso del emulador"
    DiagnosticStage.ADB -> "Conexión ADB"
    DiagnosticStage.ENDPOINT -> "Endpoint autenticado"
    DiagnosticStage.GRPC -> "Servicio gRPC"
    DiagnosticStage.FIRST_FRAME -> "Primer frame"
}
