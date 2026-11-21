package dev.picon.androidhub.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DeviceHeading(name: String, ready: Boolean, modifier: Modifier = Modifier,
                           fontSize: TextUnit = 16.sp, maxLines: Int = 1) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TooltipArea(modifier = Modifier.weight(1f, fill = false), tooltip = {
            Surface(shape = RoundedCornerShape(6.dp), shadowElevation = 3.dp) {
                Text(name, Modifier.padding(8.dp), fontSize = 12.sp)
            }
        }) {
            Text(name, fontSize = fontSize, fontWeight = FontWeight.SemiBold,
                maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
        if (ready) ReadyBadge()
    }
}

@Composable
private fun ReadyBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .semantics(mergeDescendants = true) { }
            .background(Color(0xFFDCFCE7), RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(Modifier.size(6.dp)) { drawCircle(Color(0xFF16A34A)) }
        UiText("Listo", color = Color(0xFF166534), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
