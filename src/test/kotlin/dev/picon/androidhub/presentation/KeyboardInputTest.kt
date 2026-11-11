package dev.picon.androidhub.presentation

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.InternalComposeUiApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(InternalComposeUiApi::class)
class KeyboardInputTest {
    @Test
    fun `desktop typed event sends one character and physical events do not duplicate it`() {
        val serial = "emulator-5554"
        assertNull(keyboardAction(serial, KeyEvent(Key.H, KeyEventType.KeyDown, codePoint = 'h'.code)))
        assertEquals(
            HubAction.Text(serial, "h"),
            keyboardAction(serial, KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = 'h'.code)),
        )
        assertNull(keyboardAction(serial, KeyEvent(Key.H, KeyEventType.KeyUp, codePoint = 'h'.code)))
    }

    @Test
    fun `escape is an atomic Android back keypress`() {
        assertEquals(
            HubAction.Key("emulator-5554", "GoBack"),
            keyboardAction("emulator-5554", KeyEvent(Key.Escape, KeyEventType.KeyDown)),
        )
        assertNull(keyboardAction("emulator-5554", KeyEvent(Key.Escape, KeyEventType.KeyUp)))
    }
}
