package dev.picon.androidhub.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.picon.androidhub.core.TouchInput
import dev.picon.androidhub.core.DeviceStatus
import dev.picon.androidhub.core.DisplaySurface
import dev.picon.androidhub.core.FoldPosture
import kotlinx.coroutines.flow.StateFlow

private val background = Color(0xFFF3F4F6)
private val surface = Color(0xFFE9EBEF)
private val accent = Color(0xFF355D27)
private val selection = Color(0xFFD2DEC2)
private val secondary = Color(0xFF5C626B)

@Composable
fun HubScreen(
    state: HubUiState,
    frames: StateFlow<RenderedFrame?>,
    onAction: (HubAction) -> Unit,
    reduceMotion: Boolean = false,
    onCreate: () -> Unit = {},
) {
    val selectedDevice = state.devices.firstOrNull { it.id == state.selectedDeviceId }
    val focusManager = LocalFocusManager.current
    val inputMode = LocalInputModeManager.current
    MaterialTheme(colorScheme = lightColorScheme(
        primary = accent,
        background = background,
        surface = surface,
        secondaryContainer = selection,
        onSecondaryContainer = accent,
        onBackground = Color(0xFF202124),
        onSurface = Color(0xFF202124),
    )) {
        AvdDeletionDialog(state.deleteTarget, state.deleting, state.deleteError, onAction)
        Row(Modifier.fillMaxSize().background(background).onPreviewKeyEvent { event ->
            if (event.key == Key.Tab && event.type == KeyEventType.KeyDown) {
                inputMode.requestInputMode(InputMode.Keyboard)
                focusManager.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                true
            } else false
        }) {
            Column(Modifier.width(260.dp).fillMaxHeight().background(surface).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Kasane", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(enabled = !state.closing && state.deleteTarget == null, onClick = onCreate, contentPadding = PaddingValues(4.dp)) { Text("+ Crear") }
                }
                Spacer(Modifier.height(32.dp))
                Text("EMULADORES", fontSize = 11.sp, color = secondary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.devices, key = { it.id }) { emulator ->
                        DeviceInventoryRow(emulator, state.selectedDeviceId == emulator.id, !state.closing && !state.deleting,
                            onSelect = { onAction(HubAction.SelectDevice(emulator.id)) },
                            onDelete = { onAction(HubAction.RequestDelete(emulator.id)) })
                    }
                }
                TextButton(enabled = !state.closing, onClick = { onAction(HubAction.Refresh) }) { Text("Actualizar emuladores") }
                Text("Preview · transporte propio", fontSize = 11.sp, color = secondary)
            }
            VerticalDivider(color = Color(0xFFCFD3D9))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(selectedDevice?.name ?: state.selected?.name ?: "Emuladores Android", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(listOfNotNull(selectedDevice?.api?.takeIf { it.isNotBlank() }?.let { "API $it" }, state.selected?.serial, state.posture?.label()).joinToString(" · ").ifEmpty { "macOS" }, fontSize = 12.sp, color = secondary)
                    }
                    if (state.connected && !state.closing) Text("●  Listo", color = accent, fontSize = 13.sp)
                    if (state.connecting || state.closing || selectedDevice?.status == DeviceStatus.STARTING) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                HorizontalDivider(color = Color(0xFFCFD3D9))
                Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    if (state.connected && !state.closing && state.closeError == null && state.selected != null) {
                        EmulatorDisplay(state, frames, onAction, reduceMotion)
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(state.message, color = secondary)
                            if (state.closeError != null) {
                                Text(state.closeError, color = MaterialTheme.colorScheme.error)
                                OutlinedButton(onClick = { onAction(HubAction.Close) }) { Text("Reintentar apagado") }
                            } else if (!state.closing && selectedDevice?.status in listOf(DeviceStatus.OFF, DeviceStatus.ERROR)) {
                                Button(onClick = { selectedDevice?.let { onAction(HubAction.Start(it.id)) } }) { Text("Iniciar emulador") }
                            } else if (!state.connecting && !state.closing && state.selected != null) {
                                OutlinedButton(onClick = { onAction(HubAction.Select(state.selected)) }) { Text("Volver a conectar") }
                            }
                        }
                    }
                }
                Text(
                    if (state.closing) "Esperando la salida de los emuladores…" else "Al cerrar el hub se apagarán los emuladores de la lista.",
                    Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    fontSize = 12.sp,
                    color = secondary,
                )
            }
        }
    }
}

/** Frame collection is confined to the display so streaming does not recompose the sidebar. */
@Composable
private fun EmulatorDisplay(
    state: HubUiState,
    frames: StateFlow<RenderedFrame?>,
    onAction: (HubAction) -> Unit,
    reduceMotion: Boolean,
) {
    val emulator = state.selected ?: return
    val serial = emulator.serial
    val frame by frames.collectAsState()
    val incoming = frame?.takeIf { it.serial == serial } ?: return
    val latestFrame by rememberUpdatedState(incoming)
    val focusRequester = remember { FocusRequester() }
    val latestAction by rememberUpdatedState(onAction)
    var focused by remember { mutableStateOf(false) }
    var retained by remember(serial) { mutableStateOf<RenderedFrame?>(null) }
    var inputReady by remember(serial) { mutableStateOf(false) }
    val opacity = remember(serial) { Animatable(0f) }
    LaunchedEffect(serial, incoming.surface, reduceMotion) {
        inputReady = false
        latestAction(HubAction.Release(serial))
        if (retained != null && !reduceMotion) {
            opacity.animateTo(0f, tween(120, easing = FastOutSlowInEasing))
        }
        retained = latestFrame
        if (reduceMotion) opacity.snapTo(1f)
        else opacity.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
        inputReady = true
    }
    val current = if (inputReady && retained?.surface == incoming.surface) incoming else retained
    if (current == null) return
    val acceptingInput = inputReady && current.surface == incoming.surface && !state.controlPending
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.aspectRatio(current.bitmap.width.toFloat() / current.bitmap.height, matchHeightConstraintsFirst = true)
                    .graphicsLayer { alpha = opacity.value }
                    .border(if (focused) 2.dp else 1.dp, if (focused) accent else Color(0xFFCFD3D9), RoundedCornerShape(12.dp))
                    .padding(6.dp),
            ) {
                Canvas(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(7.dp))
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            focused = it.isFocused
                            if (!it.isFocused) latestAction(HubAction.Release(serial))
                        }
                        .onPreviewKeyEvent { event ->
                            val action = if (acceptingInput && event.key != Key.Tab) keyboardAction(serial, event, current.surface) else null
                            action?.let { latestAction(it) }
                            action != null
                        }
                        .focusable()
                        .pointerInput(serial, current.surface, acceptingInput) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                if (!acceptingInput) {
                                    down.consume()
                                    return@awaitEachGesture
                                }
                                focusRequester.requestFocus()
                                fun send(x: Float, y: Float, pressed: Boolean) {
                                    latestAction(HubAction.Touch(serial, TouchInput(
                                        (x / size.width).coerceIn(0f, 1f),
                                        (y / size.height).coerceIn(0f, 1f),
                                        pressed,
                                        current.surface,
                                    )))
                                }
                                try {
                                    send(down.position.x, down.position.y, true)
                                    down.consume()
                                    do {
                                        val change = awaitPointerEvent().changes.first { it.id == down.id }
                                        send(change.position.x, change.position.y, change.pressed)
                                        change.consume()
                                    } while (change.pressed)
                                } finally {
                                    latestAction(HubAction.Release(serial))
                                }
                            }
                        },
                ) {
                    drawImage(current.bitmap, dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        EmulatorActionBar(state.capabilities, current.surface.posture, acceptingInput,
            onCommand = { command -> latestAction(HubAction.Control(emulator.instanceId, command, current.surface)) })
        state.controlError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}

private fun KeyEvent.navigationKey(): String? = when (key) {
    Key.Enter, Key.NumPadEnter -> "Enter"
    Key.Backspace -> "Backspace"
    Key.Delete -> "Delete"
    Key.Tab -> "Tab"
    Key.Escape -> "GoBack"
    Key.DirectionUp -> "ArrowUp"
    Key.DirectionDown -> "ArrowDown"
    Key.DirectionLeft -> "ArrowLeft"
    Key.DirectionRight -> "ArrowRight"
    Key.Home -> "GoHome"
    else -> null
}

internal fun keyboardAction(serial: String, event: KeyEvent, surface: DisplaySurface? = null): HubAction? {
    val navigation = event.navigationKey()
    if (event.type == KeyEventType.KeyDown && navigation != null) return HubAction.Key(serial, navigation, surface)
    // Desktop emits characters in AWT KEY_TYPED events, represented by Compose as Unknown.
    // KeyDown/KeyUp represent physical keys and must not duplicate the typed character.
    if (event.type != KeyEventType.Unknown || event.isCtrlPressed || event.isMetaPressed) return null
    val character = event.utf16CodePoint.toChar()
    return if (character != '\uFFFF' && !character.isISOControl()) HubAction.Text(serial, character.toString(), surface) else null
}

internal fun DeviceStatus.label(): String = when (this) {
    DeviceStatus.OFF -> "Apagado"
    DeviceStatus.STARTING -> "Iniciando"
    DeviceStatus.READY -> "Listo"
    DeviceStatus.ERROR -> "Error"
}
