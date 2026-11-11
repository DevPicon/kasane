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

@Composable
internal fun DeviceInventoryRow(device: AvailableEmulator, selected: Boolean, enabled: Boolean,
    onSelect: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    var options by remember { mutableStateOf(false) }
    ContextMenuArea(items = {
        if (enabled && device.canDelete()) listOf(ContextMenuItem("Eliminar…", onDelete)) else emptyList()
    }) {
        Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFFD2DEC2) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onSelect).padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FormFactorIcon(device.formFactor, Modifier.size(24.dp, 32.dp), color = if (selected) Color(0xFF355D27) else Color(0xFF5C626B))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(device.api.takeIf { it.isNotBlank() }?.let { "API $it" }, device.status.label()).joinToString(" · "),
                    fontSize = 12.sp, color = if (device.status == DeviceStatus.ERROR) MaterialTheme.colorScheme.error else Color(0xFF5C626B))
            }
            Box {
                TextButton(enabled = enabled, onClick = { options = true }, contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.width(32.dp).semantics { contentDescription = "Opciones de ${device.name}" }) { Text("⋯") }
                DropdownMenu(options && enabled, onDismissRequest = { options = false }) {
                    DropdownMenuItem(text = { Text(if (device.canDelete()) "Eliminar…" else "Apaga el emulador para eliminarlo") },
                        enabled = device.canDelete(), onClick = { options = false; onDelete() })
                }
            }
        }
    }
}

@Composable
internal fun AvdDeletionDialog(target: AvailableEmulator?, deleting: Boolean, error: String?, onAction: (HubAction) -> Unit) {
    if (target == null) return
    AlertDialog(onDismissRequest = { if (!deleting) onAction(HubAction.CancelDelete) },
        title = { Text("¿Eliminar ${target.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Se borrarán permanentemente este emulador, sus aplicaciones, datos y snapshots. Las imágenes Android compartidas del SDK se conservarán.")
                if (deleting) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Eliminando emulador…") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(enabled = !deleting, onClick = { onAction(HubAction.CancelDelete) }) { Text("Cancelar") } },
        confirmButton = { TextButton(enabled = !deleting, onClick = { onAction(HubAction.ConfirmDelete) },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Eliminar emulador") } })
}
