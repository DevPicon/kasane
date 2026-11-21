package dev.picon.androidhub.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.picon.androidhub.core.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class LanguageSelectorTest {
    @get:Rule val compose = createComposeRule()
    @Test fun languageChangesTooltipsAndAccessibilityWithoutTranslatingDeviceNames() {
        val language = mutableStateOf(AppLanguage.ENGLISH)
        compose.setContent {
            CompositionLocalProvider(LocalUiStrings provides UiStrings(language.value)) {
                MaterialTheme { Column {
                    LanguageSelector(language.value, { language.value = it })
                    CaptureToolbar(CaptureState(), RunningEmulator("emulator-5554", "Mi teléfono", "A"), true, {})
                    DeviceInventoryRow(AvailableEmulator("A", "Mi teléfono", "36", DeviceStatus.OFF), true, true, DeviceInventoryActions({}, {}))
                } }
            }
        }
        compose.onNodeWithContentDescription("Take screenshot").assertExists()
        compose.onNodeWithText("Mi teléfono").assertExists()
        compose.onNodeWithContentDescription("Language").performClick()
        compose.onNodeWithText("Español").performClick()
        compose.onNodeWithContentDescription("Obtener captura de pantalla").assertExists()
        compose.onNodeWithContentDescription("Idioma").performClick()
        compose.onNodeWithText("Português").performClick()
        compose.onNodeWithContentDescription("Obter captura de tela").assertExists()
        compose.onNodeWithContentDescription("Gravar vídeo").assertExists()
        compose.onNodeWithText("Mi teléfono").assertExists()
    }
}
