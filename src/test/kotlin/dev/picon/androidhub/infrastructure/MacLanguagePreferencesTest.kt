package dev.picon.androidhub.infrastructure

import dev.picon.androidhub.core.AppLanguage
import kotlinx.coroutines.test.runTest
import java.util.prefs.AbstractPreferences
import kotlin.test.*

class MacLanguagePreferencesTest {
    @Test fun `language survives a new settings instance and corrupt values fall back to English`() = runTest {
        val node = MemoryPreferences()
        val first = MacLanguagePreferences(node)
        assertEquals(AppLanguage.ENGLISH, first.read())
        assertTrue(first.write(AppLanguage.PORTUGUESE))
        assertEquals(AppLanguage.PORTUGUESE, MacLanguagePreferences(node).read())
        node.put("language", "unknown")
        assertEquals(AppLanguage.ENGLISH, first.read())
        node.fail = true
        assertFalse(first.write(AppLanguage.SPANISH))
    }
}
private class MemoryPreferences : AbstractPreferences(null, "") {
    val values = mutableMapOf<String, String>()
    var fail = false
    override fun putSpi(key: String, value: String) { if (fail) error("Preferences unavailable"); values[key] = value }
    override fun getSpi(key: String): String? = values[key]
    override fun removeSpi(key: String) { values.remove(key) }
    override fun removeNodeSpi() { values.clear() }
    override fun keysSpi(): Array<String> = values.keys.toTypedArray()
    override fun childrenNamesSpi(): Array<String> = emptyArray()
    override fun childSpi(name: String): AbstractPreferences = error("No children")
    override fun syncSpi() = Unit
    override fun flushSpi() = Unit
}
