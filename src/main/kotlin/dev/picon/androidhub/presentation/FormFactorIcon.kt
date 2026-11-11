package dev.picon.androidhub.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.picon.androidhub.core.FormFactor

@Composable
internal fun FormFactorIcon(
    formFactor: FormFactor,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
) {
    Canvas(modifier.semantics {
        contentDescription = formFactor.label()
        role = Role.Image
    }) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.5.dp.toPx())
        fun panel(left: Float, top: Float, right: Float, bottom: Float) {
            drawRoundRect(color, Offset(w * left, h * top), Size(w * (right - left), h * (bottom - top)),
                CornerRadius(2.dp.toPx()), style = stroke)
        }
        when (formFactor) {
            FormFactor.HANDSET -> {
                panel(.22f, .08f, .78f, .92f)
                drawLine(color, Offset(w * .42f, h * .17f), Offset(w * .58f, h * .17f), stroke.width)
            }
            FormFactor.FOLDABLE -> {
                drawPath(Path().apply {
                    moveTo(w * .06f, h * .18f); lineTo(w * .5f, h * .29f)
                    lineTo(w * .94f, h * .18f); lineTo(w * .94f, h * .79f)
                    lineTo(w * .5f, h * .9f); lineTo(w * .06f, h * .79f); close()
                    moveTo(w * .5f, h * .29f); lineTo(w * .5f, h * .9f)
                }, color, style = stroke)
            }
            FormFactor.TABLET -> {
                panel(.05f, .2f, .95f, .8f)
                drawCircle(color, radius = .7.dp.toPx(), center = Offset(w * .12f, h * .5f))
            }
            FormFactor.WEARABLE -> {
                panel(.34f, .03f, .66f, .23f)
                panel(.34f, .77f, .66f, .97f)
                drawCircle(color, radius = w * .37f, center = center, style = stroke)
                drawLine(color, center, Offset(w * .5f, h * .36f), stroke.width)
                drawLine(color, center, Offset(w * .68f, h * .54f), stroke.width)
            }
            FormFactor.TV -> {
                panel(.04f, .18f, .96f, .7f)
                drawLine(color, Offset(w * .5f, h * .7f), Offset(w * .5f, h * .86f), stroke.width)
                drawLine(color, Offset(w * .29f, h * .86f), Offset(w * .71f, h * .86f), stroke.width)
            }
            FormFactor.AUTOMOTIVE -> {
                panel(.04f, .22f, .96f, .69f)
                drawCircle(color, radius = w * .17f, center = Offset(w * .29f, h * .45f), style = stroke)
                drawLine(color, Offset(w * .6f, h * .39f), Offset(w * .81f, h * .39f), stroke.width)
                drawLine(color, Offset(w * .6f, h * .52f), Offset(w * .81f, h * .52f), stroke.width)
                drawLine(color, Offset(w * .12f, h * .8f), Offset(w * .88f, h * .8f), stroke.width)
            }
            FormFactor.UNKNOWN -> {
                panel(.18f, .13f, .82f, .87f)
                drawCircle(color, radius = 1.dp.toPx(), center = center)
            }
        }
    }
}

internal fun FormFactor.label(): String = when (this) {
    FormFactor.HANDSET -> "Teléfono"
    FormFactor.FOLDABLE -> "Plegable"
    FormFactor.TABLET -> "Tablet"
    FormFactor.WEARABLE -> "Reloj"
    FormFactor.TV -> "TV"
    FormFactor.AUTOMOTIVE -> "Automóvil"
    FormFactor.UNKNOWN -> "Tipo de dispositivo desconocido"
}
