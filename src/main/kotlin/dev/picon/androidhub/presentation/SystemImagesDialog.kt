package dev.picon.androidhub.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState

@Composable
fun SystemImagesDialog(state: SystemImagesState, onAction: (SystemImagesAction) -> Unit) {
    if (!state.visible) return
    DialogWindow(onCloseRequest = { onAction(SystemImagesAction.Cancel) }, title = LocalUiStrings.current.text("Imágenes Android"), resizable = false,
        state = rememberDialogState(width = 760.dp, height = 660.dp)) {
        SystemImagesContent(state, onAction, Modifier.fillMaxSize())
    }
}

@Composable
internal fun SystemImagesContent(state: SystemImagesState, onAction: (SystemImagesAction) -> Unit, modifier: Modifier = Modifier) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF355D27), surface = Color(0xFFF3F4F6))) {
        Surface(modifier) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UiText("Imágenes Android", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                UiText(state.profile?.let { "Imágenes estables para ${it.name} y la arquitectura de este Mac." }
                    ?: "Imágenes estables compatibles con la arquitectura de este Mac.", fontSize = 13.sp)
                UiText("La descarga instala un paquete compartido del SDK. No crea ni inicia emuladores. El CLI no publica tamaños; se comprueban al menos 6 GB libres antes de instalar.",
                    fontSize = 12.sp, color = Color(0xFF5C626B))
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!state.loading && state.compatible.isEmpty()) item { UiText("No se encontraron imágenes compatibles. Revisa la conexión y recarga el catálogo.") }
                    items(state.compatible, key = { it.image.id }) { entry ->
                        val enabled = !entry.installed && !state.installing && !state.loading
                        Surface(color = if (entry.image.id == state.selectedId) Color(0xFFD2DEC2) else Color(0xFFE9EBEF), shape = MaterialTheme.shapes.medium) {
                            Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onAction(SystemImagesAction.Select(entry.image.id)) }.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = entry.image.id == state.selectedId, enabled = enabled,
                                    onClick = { onAction(SystemImagesAction.Select(entry.image.id)) })
                                Column(Modifier.weight(1f)) {
                                    Text(entry.image.label, fontSize = 13.sp)
                                    UiText(if (entry.installed) "Instalada" else "Disponible para descargar", fontSize = 12.sp, color = Color(0xFF5C626B))
                                }
                            }
                        }
                    }
                }
                if (state.installing) {
                    val percent = state.progress.percent
                    if (percent == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(listOfNotNull(percent?.let { "$it %" }, state.progress.message).joinToString(" · "), fontSize = 12.sp)
                }
                state.installedId?.let { UiText("Imagen instalada. Ya está disponible en Crear emulador.", color = Color(0xFF355D27), fontSize = 13.sp) }
                state.error?.let { UiText(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !state.loading && !state.installing, onClick = { onAction(SystemImagesAction.Refresh) }) { UiText("Recargar") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onAction(SystemImagesAction.Cancel) }) { UiText(if (state.installing) "Cancelar descarga" else "Volver") }
                    Button(enabled = state.canInstall, onClick = { onAction(SystemImagesAction.Install) }) { UiText("Descargar imagen") }
                }
            }
        }
    }
}
