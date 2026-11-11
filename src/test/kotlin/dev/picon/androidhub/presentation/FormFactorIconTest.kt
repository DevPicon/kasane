package dev.picon.androidhub.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.picon.androidhub.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class FormFactorIconTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun galleryRendersEveryCategoryWithAccessibleNames() {
        compose.setContent {
            MaterialTheme {
                Row(Modifier.background(Color(0xFFF3F4F6)).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    FormFactor.entries.forEach { type ->
                        Column(Modifier.width(100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            FormFactorIcon(type, Modifier.size(36.dp, 48.dp), Color(0xFF5C626B))
                            Spacer(Modifier.height(12.dp))
                            Text(if (type == FormFactor.UNKNOWN) "Desconocido" else type.label(), fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        FormFactor.entries.forEach { compose.onNodeWithContentDescription(it.label()).assertIsDisplayed() }
        val captured = compose.onRoot().captureToImage()
        val pixels = captured.toPixelMap()
        val bitmap = BufferedImage(captured.width, captured.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until captured.height) for (x in 0 until captured.width) bitmap.setRGB(x, y, pixels[x, y].toArgb())
        val path = Path.of("build/validation/form-factor-icons.png")
        Files.createDirectories(path.parent)
        ImageIO.write(bitmap, "png", path.toFile())
    }

    @Test
    fun sidebarShowsTypesBeforeStartupAndRetainsThemWhenStatusChanges() {
        val initial = listOf(
            AvailableEmulator("a", "Alpha", "36", DeviceStatus.OFF, formFactor = FormFactor.HANDSET),
            AvailableEmulator("b", "Beta", "36", DeviceStatus.OFF, formFactor = FormFactor.FOLDABLE),
            AvailableEmulator("c", "Gamma", "36", DeviceStatus.OFF, formFactor = FormFactor.TABLET),
        )
        val state = mutableStateOf(HubUiState(devices = initial))
        compose.setContent { HubScreen(state.value, MutableStateFlow(null), {}) }
        for (label in listOf("Teléfono", "Plegable", "Tablet")) compose.onNodeWithContentDescription(label, useUnmergedTree = true).assertExists()
        compose.runOnIdle { state.value = state.value.copy(devices = initial.map { it.copy(status = DeviceStatus.STARTING) }) }
        for (label in listOf("Teléfono", "Plegable", "Tablet")) compose.onNodeWithContentDescription(label, useUnmergedTree = true).assertExists()
    }
}
