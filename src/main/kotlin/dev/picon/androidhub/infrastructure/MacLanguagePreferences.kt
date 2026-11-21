package dev.picon.androidhub.infrastructure

import dev.picon.androidhub.core.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.prefs.Preferences

internal class MacLanguagePreferences(private val preferences: Preferences = Preferences.userRoot().node("dev/picon/kasane")) {
    suspend fun read(): AppLanguage = withContext(Dispatchers.IO) {
        try { AppLanguage.fromCode(preferences.get("language", null)) } catch (_: Exception) { AppLanguage.ENGLISH }
    }
    suspend fun write(language: AppLanguage): Boolean = withContext(Dispatchers.IO) {
        try { preferences.put("language", language.code); preferences.flush(); true } catch (_: Exception) { false }
    }
}
