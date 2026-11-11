package dev.picon.androidhub.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState

@Composable
fun AvdCreationDialog(state: AvdCreationState, onAction: (AvdCreationAction) -> Unit, onManageImages: () -> Unit = {}) {
    if (!state.visible) return
    DialogWindow(onCloseRequest = { onAction(AvdCreationAction.Cancel) }, title = "Crear emulador", resizable = false,
        state = rememberDialogState(width = 620.dp, height = 660.dp)) {
        AvdCreationContent(state, onAction, Modifier.fillMaxSize(), onManageImages)
    }
}

@Composable
internal fun AvdCreationContent(state: AvdCreationState, onAction: (AvdCreationAction) -> Unit, modifier: Modifier = Modifier, onManageImages: () -> Unit = {}) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF355D27), surface = Color(0xFFF3F4F6))) {
        Surface(modifier) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Crear emulador", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Elige un perfil y una imagen instalada en este equipo.", color = Color(0xFF5C626B), fontSize = 13.sp)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val catalog = state.catalog
                    when {
                        state.loading -> {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text("Cargando perfiles e imágenes…")
                        }
                        catalog == null -> Text("No se pudo cargar el catálogo local.")
                        catalog.images.isEmpty() -> Text("No hay imágenes Android instaladas compatibles con este equipo. Usa Descargar imágenes para instalar una.")
                        catalog.profiles.isEmpty() -> Text("No hay perfiles compatibles con las imágenes instaladas y el CLI disponible.")
                        else -> {
                            OutlinedTextField(state.name, { onAction(AvdCreationAction.Name(it)) }, label = { Text("Nombre del AVD") },
                                placeholder = { Text("Mi_Emulador") }, enabled = !state.creating, singleLine = true, modifier = Modifier.fillMaxWidth())
                            ChoiceField("Perfil de dispositivo", catalog.profiles.map { it.id to "${it.name} · ${it.formFactor.label()}" }, state.profileId,
                                !state.creating, { onAction(AvdCreationAction.Profile(it)) }, Modifier.fillMaxWidth())
                            val profile = catalog.profiles.firstOrNull { it.id == state.profileId }
                            ChoiceField("Imagen Android", catalog.images.filter { profile?.supports(it) == true }.map { it.id to it.label }, state.imageId,
                                !state.creating, { onAction(AvdCreationAction.Image(it)) }, Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(state.ram, { onAction(AvdCreationAction.Ram(it)) }, label = { Text("RAM (MB)") },
                                    enabled = !state.creating, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(state.storage, { onAction(AvdCreationAction.Storage(it)) }, label = { Text("Almacenamiento (GB)") },
                                    enabled = !state.creating, singleLine = true, modifier = Modifier.weight(1f))
                            }
                            Text("El AVD quedará apagado y aparecerá en tu lista. Puedes ajustar los recursos antes de crearlo.", fontSize = 12.sp, color = Color(0xFF5C626B))
                            if (state.name.isNotEmpty() && !state.creating) state.validation?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                        }
                    }
                    if (!state.creating && !state.loading) OutlinedButton(onClick = onManageImages) { Text("Descargar imágenes…") }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    if (state.creating) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Creando el AVD…", fontSize = 13.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (!state.loading && !state.creating) TextButton(onClick = { onAction(AvdCreationAction.Open) }) { Text("Recargar") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onAction(AvdCreationAction.Cancel) }) { Text("Cancelar") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = state.canCreate, onClick = { onAction(AvdCreationAction.Submit) }) { Text("Crear emulador") }
                }
            }
        }
    }
}

@Composable
private fun ChoiceField(label: String, choices: List<Pair<String, String>>, selected: String, enabled: Boolean,
    onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = Color(0xFF5C626B))
        Spacer(Modifier.height(4.dp))
        OutlinedButton(enabled = enabled, onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(choices.firstOrNull { it.first == selected }?.second ?: "Seleccionar", modifier = Modifier.weight(1f))
            Text("⌄")
        }
        DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 300.dp)) {
            choices.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(id) }) }
        }
    }
}
