package dev.picon.androidhub.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.picon.androidhub.core.RunningEmulator
import kotlinx.coroutines.delay

@Composable
internal fun CaptureToolbar(state: CaptureState, selected: RunningEmulator?, enabled: Boolean,
                            onAction: (CaptureAction) -> Unit, modifier: Modifier = Modifier) {
    var confirmDiscard by remember(state.pendingVideo) { mutableStateOf(false) }
    if (confirmDiscard && state.pendingVideo != null) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { UiText("¿Descartar esta grabación?") },
        text = { UiText("El video temporal se eliminará. Esta acción no se puede deshacer.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onAction(CaptureAction.Discard) }) { UiText("Descartar") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { UiText("Cancelar") } })
    var elapsed by remember(state.startedAtNanos) { mutableStateOf(0L) }
    LaunchedEffect(state.startedAtNanos) {
        val began = state.startedAtNanos ?: return@LaunchedEffect
        while (true) { elapsed = (System.nanoTime() - began) / 1_000_000_000; delay(250) }
    }
    Column(modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            CaptureIconButton("Obtener captura de pantalla", CaptureGlyph.CAMERA,
                enabled && selected != null && !state.busy && state.pendingVideo == null,
                { selected?.let { onAction(CaptureAction.Screenshot(it)) } })
            when {
                state.recording != null -> {
                    CaptureIconButton("Detener grabación", CaptureGlyph.STOP, !state.stopping, { onAction(CaptureAction.Stop) })
                    if (state.startedAtNanos == null || state.stopping) UiText(if (state.stopping) "Finalizando grabación…" else "Preparando grabación…", fontSize = 12.sp)
                    else Text("● ${state.recording.name} · %02d:%02d / 03:00".format(elapsed / 60, elapsed % 60),
                        color = Color(0xFFB3261E), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                state.pendingVideo != null -> {
                    CaptureIconButton("Guardar grabación", CaptureGlyph.SAVE, !state.busy, { onAction(CaptureAction.Save) })
                    CaptureIconButton("Descartar grabación", CaptureGlyph.DISCARD, !state.busy, { confirmDiscard = true })
                    UiText("Grabación pendiente de guardar", fontSize = 12.sp)
                }
                else -> {
                    CaptureIconButton("Grabar video", CaptureGlyph.VIDEO, enabled && selected != null && !state.busy,
                        { selected?.let { onAction(CaptureAction.Record(it)) } })
                    Row(Modifier.toggleable(value = state.showTaps, enabled = !state.busy && enabled, role = Role.Checkbox,
                        onValueChange = { onAction(CaptureAction.ShowTaps(it)) }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.showTaps, enabled = !state.busy && enabled, onCheckedChange = null)
                        UiText("Mostrar taps", fontSize = 12.sp)
                    }
                }
            }
        }
        state.savedPath?.let { UiText("Guardado: $it", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        state.error?.let { UiText(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    }
}

private enum class CaptureGlyph { CAMERA, VIDEO, STOP, SAVE, DISCARD }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CaptureIconButton(label: String, glyph: CaptureGlyph, enabled: Boolean, onClick: () -> Unit) {
    val name = LocalUiStrings.current.text(label)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    TooltipArea(tooltip = { Surface(shape = RoundedCornerShape(6.dp), shadowElevation = 3.dp) {
        Text(name, Modifier.padding(8.dp), fontSize = 12.sp)
    } }) {
        IconButton(onClick, enabled = enabled, interactionSource = interaction,
            modifier = Modifier.size(40.dp).semantics { contentDescription = name }.border(
                if (focused) 2.dp else 0.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))) {
            val color = if (glyph == CaptureGlyph.STOP && enabled) Color(0xFFB3261E) else LocalContentColor.current
            Canvas(Modifier.size(20.dp)) {
                val w = size.width; val h = size.height; val stroke = Stroke(1.8.dp.toPx())
                when (glyph) {
                    CaptureGlyph.CAMERA -> {
                        drawRoundRect(color, Offset(w*.08f,h*.25f), Size(w*.84f,h*.6f), CornerRadius(2.dp.toPx()), style = stroke)
                        drawCircle(color,w*.19f,Offset(w*.5f,h*.55f),style=stroke)
                        drawLine(color,Offset(w*.3f,h*.15f),Offset(w*.65f,h*.15f),stroke.width)
                    }
                    CaptureGlyph.VIDEO -> {
                        drawRoundRect(color,Offset(w*.05f,h*.25f),Size(w*.58f,h*.5f),CornerRadius(2.dp.toPx()),style=stroke)
                        drawPath(Path().apply { moveTo(w*.63f,h*.4f);lineTo(w*.93f,h*.23f);lineTo(w*.93f,h*.77f);lineTo(w*.63f,h*.6f) }, color,style=stroke)
                    }
                    CaptureGlyph.STOP -> drawRoundRect(color,Offset(w*.15f,h*.15f),Size(w*.7f,h*.7f),CornerRadius(2.dp.toPx()))
                    CaptureGlyph.SAVE -> {
                        drawRoundRect(color,Offset(w*.12f,h*.1f),Size(w*.76f,h*.8f),CornerRadius(1.dp.toPx()),style=stroke)
                        drawRect(color,Offset(w*.3f,h*.1f),Size(w*.4f,h*.27f),style=stroke)
                        drawRect(color,Offset(w*.3f,h*.58f),Size(w*.4f,h*.32f),style=stroke)
                    }
                    CaptureGlyph.DISCARD -> {
                        drawLine(color,Offset(w*.15f,h*.25f),Offset(w*.85f,h*.25f),stroke.width)
                        drawLine(color,Offset(w*.38f,h*.12f),Offset(w*.62f,h*.12f),stroke.width)
                        drawPath(Path().apply { moveTo(w*.25f,h*.3f);lineTo(w*.3f,h*.9f);lineTo(w*.7f,h*.9f);lineTo(w*.75f,h*.3f) },color,style=stroke)
                    }
                }
            }
        }
    }
}
