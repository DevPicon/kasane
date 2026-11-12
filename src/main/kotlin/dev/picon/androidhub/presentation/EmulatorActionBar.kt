package dev.picon.androidhub.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.picon.androidhub.core.EmulatorCapabilities
import dev.picon.androidhub.core.EmulatorCommand
import dev.picon.androidhub.core.FoldPosture

@Composable
internal fun EmulatorActionBar(
    capabilities: EmulatorCapabilities,
    posture: FoldPosture?,
    enabled: Boolean,
    onCommand: (EmulatorCommand) -> Unit,
    modifier: Modifier = Modifier,
    geometryEnabled: Boolean = true,
) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCFD3D9))) {
        FlowRow(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            if (capabilities.navigation) {
                ControlButton("Atrás", EmulatorCommand.Back, enabled, onCommand)
                ControlButton("Inicio", EmulatorCommand.Home, enabled, onCommand)
                ControlButton("Recientes", EmulatorCommand.Recents, enabled, onCommand)
            }
            if (capabilities.rotation) ControlButton("Rotar 90°", EmulatorCommand.Rotate, enabled && geometryEnabled, onCommand)
            if (capabilities.postures.isNotEmpty()) VerticalDivider(
                Modifier.height(24.dp).padding(horizontal = 4.dp), color = Color(0xFFCFD3D9),
            )
            capabilities.postures.forEach { available ->
                ControlButton(
                    "Postura ${available.label()}", EmulatorCommand.SetPosture(available), enabled && geometryEnabled, onCommand,
                    selected = posture == available,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ControlButton(
    label: String,
    command: EmulatorCommand,
    enabled: Boolean,
    onCommand: (EmulatorCommand) -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    TooltipArea(tooltip = {
        Surface(shape = RoundedCornerShape(6.dp), shadowElevation = 3.dp) {
            Text(label, Modifier.padding(8.dp), fontSize = 12.sp)
        }
    }, modifier = modifier) {
        val buttonModifier = Modifier
            .size(40.dp)
            .semantics {
                contentDescription = label
                if (command is EmulatorCommand.SetPosture) this.selected = selected
            }
            .border(if (focused) 2.dp else 0.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
        if (command is EmulatorCommand.SetPosture) {
            IconToggleButton(checked = selected, onCheckedChange = { onCommand(command) }, enabled = enabled,
                interactionSource = interactions, modifier = buttonModifier,
                colors = IconButtonDefaults.iconToggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )) {
                ControlIcon(command, Modifier.size(20.dp))
            }
        } else {
            IconButton(onClick = { onCommand(command) }, enabled = enabled, interactionSource = interactions,
                modifier = buttonModifier) {
                ControlIcon(command, Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ControlIcon(command: EmulatorCommand, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier) {
        val stroke = Stroke(width = 1.8.dp.toPx())
        val w = size.width
        val h = size.height
        when (command) {
            EmulatorCommand.Back -> drawPath(Path().apply {
                moveTo(w * .7f, h * .15f); lineTo(w * .2f, h * .5f)
                lineTo(w * .7f, h * .85f); close()
            }, color, style = stroke)
            EmulatorCommand.Home -> drawCircle(color, radius = w * .33f, style = stroke)
            EmulatorCommand.Recents -> drawRoundRect(color, Offset(w * .2f, h * .2f), Size(w * .6f, h * .6f), style = stroke)
            EmulatorCommand.Rotate -> {
                drawArc(color, 35f, 280f, false, Offset(w * .12f, h * .12f), Size(w * .76f, h * .76f), style = stroke)
                drawPath(Path().apply {
                    moveTo(w * .85f, h * .05f); lineTo(w * .85f, h * .28f); lineTo(w * .62f, h * .28f)
                }, color, style = stroke)
            }
            is EmulatorCommand.SetPosture -> {
                val outline = Path().apply {
                    when (command.posture) {
                        FoldPosture.FOLDED -> {
                            // Closed handset with a second panel just behind it.
                            moveTo(w * .63f, h * .1f); lineTo(w * .79f, h * .1f)
                            lineTo(w * .79f, h * .84f)
                            moveTo(w * .25f, h * .16f); lineTo(w * .66f, h * .16f)
                            lineTo(w * .66f, h * .9f); lineTo(w * .25f, h * .9f); close()
                        }
                        FoldPosture.HALF_OPEN -> {
                            // Open book: hinge in the center, two foreshortened panels.
                            moveTo(w * .12f, h * .17f); lineTo(w * .5f, h * .33f)
                            lineTo(w * .88f, h * .17f); lineTo(w * .88f, h * .75f)
                            lineTo(w * .5f, h * .92f); lineTo(w * .12f, h * .75f); close()
                            moveTo(w * .5f, h * .33f); lineTo(w * .5f, h * .92f)
                        }
                        FoldPosture.OPEN -> {
                            moveTo(w * .1f, h * .17f); lineTo(w * .9f, h * .17f)
                            lineTo(w * .9f, h * .83f); lineTo(w * .1f, h * .83f); close()
                            moveTo(w * .5f, h * .17f); lineTo(w * .5f, h * .83f)
                        }
                        FoldPosture.FLIPPED -> {
                            moveTo(w * .27f, h * .1f); lineTo(w * .73f, h * .1f)
                            lineTo(w * .73f, h * .9f); lineTo(w * .27f, h * .9f); close()
                            moveTo(w * .34f, h * .48f); lineTo(w * .66f, h * .48f)
                            moveTo(w * .48f, h * .32f); lineTo(w * .34f, h * .48f); lineTo(w * .48f, h * .64f)
                        }
                        FoldPosture.TENT -> {
                            moveTo(w * .1f, h * .83f); lineTo(w * .5f, h * .17f)
                            lineTo(w * .9f, h * .83f); lineTo(w * .74f, h * .83f)
                            lineTo(w * .5f, h * .43f); lineTo(w * .26f, h * .83f); close()
                        }
                    }
                }
                drawPath(outline, color, style = stroke)
            }
        }
    }
}

internal fun FoldPosture.label(): String = when (this) {
    FoldPosture.FOLDED -> "Plegado"
    FoldPosture.HALF_OPEN -> "Semiabierto"
    FoldPosture.OPEN -> "Abierto"
    FoldPosture.FLIPPED -> "Invertido"
    FoldPosture.TENT -> "Tienda"
}
