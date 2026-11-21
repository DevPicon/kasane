package dev.picon.androidhub.core

enum class AppLanguage(val code: String, val displayName: String) {
    ENGLISH("en", "English"), SPANISH("es", "Español"), PORTUGUESE("pt", "Português");
    companion object { fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: ENGLISH }
}
