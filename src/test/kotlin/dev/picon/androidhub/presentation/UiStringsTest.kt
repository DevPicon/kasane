package dev.picon.androidhub.presentation

import dev.picon.androidhub.core.AppLanguage
import kotlin.test.*

class UiStringsTest {
    @Test fun `English is default and invalid persisted values fall back to it`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode(null))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode("invalid"))
        assertEquals(AppLanguage.PORTUGUESE, AppLanguage.fromCode("pt"))
        assertEquals("Take screenshot", UiStrings(AppLanguage.ENGLISH).text("Obtener captura de pantalla"))
        assertEquals("Obter captura de tela", UiStrings(AppLanguage.PORTUGUESE).text("Obtener captura de pantalla"))
    }
    @Test fun `all translations are complete and preserve placeholders`() {
        val placeholders = Regex("\\{\\d+}")
        assertTrue(UiStrings.catalog.size > 200)
        for ((source, translations) in UiStrings.catalog) {
            assertEquals(2, translations.size)
            for (target in translations) {
                assertTrue(target.isNotBlank())
                assertEquals(placeholders.findAll(source).map { it.value }.toList().sorted(),
                    placeholders.findAll(target).map { it.value }.toList().sorted(), source)
            }
        }
    }
    @Test fun `opaque user values and technical SDK output are preserved`() {
        val name = "Folder {0} with \$ signs /照片/"
        assertEquals("Saved: $name", UiStrings(AppLanguage.ENGLISH).text("Guardado: $name"))
        assertEquals("Salvo: $name", UiStrings(AppLanguage.PORTUGUESE).text("Guardado: $name"))
        assertEquals("API 36.1 · Ready", UiStrings(AppLanguage.ENGLISH).text("API 36.1 · Listo"))
        assertEquals("Download 45% system-images;android-36", UiStrings(AppLanguage.PORTUGUESE).text("Download 45% system-images;android-36"))
    }
}
