package dev.picon.androidhub.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.picon.androidhub.core.RunningEmulator
import kotlinx.coroutines.delay

@Composable
internal fun CaptureToolbar(state: CaptureState, selected: RunningEmulator?, enabled: Boolean,
                            onAction: (CaptureAction) -> Unit, modifier: Modifier = Modifier) {
    var elapsed by remember(state.startedAtNanos) { mutableStateOf(0L) }
    LaunchedEffect(state.startedAtNanos) {
        val began = state.startedAtNanos ?: return@LaunchedEffect
        while (true) {
            elapsed = (System.nanoTime() - began) / 1_000_000_000
            delay(250)
        }
    }
    Column(modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = enabled && selected != null && !state.busy,
                onClick = { selected?.let { onAction(CaptureAction.Screenshot(it)) } }) { Text("Captura PNG") }
            if (state.recording == null) {
                OutlinedButton(enabled = enabled && selected != null && !state.busy,
                    onClick = { selected?.let { onAction(CaptureAction.Record(it)) } }) { Text("Grabar video") }
                Row(Modifier.toggleable(value = state.showTaps, enabled = !state.busy && enabled, role = Role.Checkbox,
                    onValueChange = { onAction(CaptureAction.ShowTaps(it)) }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.showTaps, enabled = !state.busy && enabled, onCheckedChange = null)
                    Text("Mostrar taps", fontSize = 12.sp)
                }
            } else {
                Text(if (state.stopping) "Guardando video…" else if (state.startedAtNanos == null) "Preparando grabación…" else
                    "● ${state.recording.name} · %02d:%02d / 03:00".format(elapsed / 60, elapsed % 60),
                    color = Color(0xFFB3261E), fontSize = 12.sp)
                Button(enabled = !state.stopping, onClick = { onAction(CaptureAction.Stop) }) { Text("Detener y guardar") }
            }
        }
        if (!state.busy && state.savedPath == null && state.error == null) Text("MP4 sin audio · máximo 3 minutos", fontSize = 11.sp)
        state.savedPath?.let { Text("Guardado: $it", fontSize = 12.sp) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    }
}
