package dev.picon.androidhub.presentation

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.picon.androidhub.core.AvailableEmulator
import dev.picon.androidhub.core.DeviceStatus

internal data class DeviceInventoryActions(
    val onSelect: () -> Unit,
    val onDelete: () -> Unit,
    val onStop: () -> Unit = {},
    val onRestart: () -> Unit = {},
    val onDiagnose: () -> Unit = {},
)

@Composable
internal fun DeviceInventoryRow(device: AvailableEmulator, selected: Boolean, enabled: Boolean,
    actions: DeviceInventoryActions, modifier: Modifier = Modifier) {
    val strings = LocalUiStrings.current
    var options by remember { mutableStateOf(false) }
    ContextMenuArea(items = {
        if (!enabled || device.powerPending()) emptyList() else buildList {
            if (device.running != null) {
                add(ContextMenuItem(strings.text("Apagar"), actions.onStop))
                add(ContextMenuItem(strings.text("Reiniciar"), actions.onRestart))
            }
            add(ContextMenuItem(strings.text("Diagnóstico…"), actions.onDiagnose))
            if (device.canDelete()) add(ContextMenuItem(strings.text("Eliminar…"), actions.onDelete))
        }
    }) {
        Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFFD2DEC2) else Color.Transparent)
            .clickable(enabled = enabled, onClick = actions.onSelect).padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FormFactorIcon(device.formFactor, Modifier.size(24.dp, 32.dp), color = if (selected) Color(0xFF355D27) else Color(0xFF5C626B))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                DeviceHeading(device.name, device.status == DeviceStatus.READY,
                    modifier = Modifier.fillMaxWidth(), fontSize = 14.sp, maxLines = 2)
                UiText(listOfNotNull(device.api.takeIf { it.isNotBlank() }?.let { "API $it" }, device.status.takeUnless { it == DeviceStatus.READY }?.label()).joinToString(" · "),
                    fontSize = 12.sp, color = if (device.status == DeviceStatus.ERROR) MaterialTheme.colorScheme.error else Color(0xFF5C626B))
            }
            Box {
                TextButton(enabled = enabled, onClick = { options = true }, contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.width(32.dp).semantics { contentDescription = strings.text("Opciones de ${device.name}") }) { UiText("⋯") }
                DropdownMenu(options && enabled, onDismissRequest = { options = false }) {
                    if (device.running != null && !device.powerPending()) {
                        DropdownMenuItem(text = { UiText("Apagar") }, onClick = { options = false; actions.onStop() })
                        DropdownMenuItem(text = { UiText("Reiniciar") }, onClick = { options = false; actions.onRestart() })
                    }
                    DropdownMenuItem(text = { UiText("Diagnóstico…") }, enabled = !device.powerPending(), onClick = { options = false; actions.onDiagnose() })
                    DropdownMenuItem(text = { UiText(if (device.canDelete()) "Eliminar…" else if (device.powerPending()) "Operación en curso…" else "Apaga el emulador para eliminarlo") },
                        enabled = device.canDelete(), onClick = { options = false; actions.onDelete() })
                }
            }
        }
    }
}

@Composable
internal fun AvdDeletionDialog(target: AvailableEmulator?, deleting: Boolean, error: String?, onAction: (HubAction) -> Unit) {
    if (target == null) return
    AlertDialog(onDismissRequest = { if (!deleting) onAction(HubAction.CancelDelete) },
        title = { UiText("¿Eliminar ${target.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UiText("Se borrarán permanentemente este emulador, sus aplicaciones, datos y snapshots. Las imágenes Android compartidas del SDK se conservarán.")
                if (deleting) { LinearProgressIndicator(Modifier.fillMaxWidth()); UiText("Eliminando emulador…") }
                error?.let { UiText(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(enabled = !deleting, onClick = { onAction(HubAction.CancelDelete) }) { UiText("Cancelar") } },
        confirmButton = { TextButton(enabled = !deleting, onClick = { onAction(HubAction.ConfirmDelete) },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { UiText("Eliminar emulador") } })
}

private fun AvailableEmulator.powerPending() = status in setOf(DeviceStatus.STOPPING, DeviceStatus.RESTARTING)
