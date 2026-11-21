package dev.picon.androidhub.presentation

import dev.picon.androidhub.core.AppLanguage
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/** The Spanish source messages are gettext-style message IDs, never user-supplied labels. */
class UiStrings(val language: AppLanguage) {
    fun text(source: String): String {
        if (language == AppLanguage.SPANISH) return source
        catalog[source]?.let { return it[if (language == AppLanguage.ENGLISH) 0 else 1] }
        for (template in templates) {
            val match = template.pattern.matchEntire(source) ?: continue
            val target = template.translations[if (language == AppLanguage.ENGLISH) 0 else 1]
            return Regex("\\{(\\d+)}").replace(target) { placeholder ->
                match.groupValues[placeholder.groupValues[1].toInt() + 1]
            }
        }
        // Composed status/diagnostic lines: authored fragments only. Device names/paths use plain Text.
        if (source.contains(" · ")) return source.split(" · ").joinToString(" · ", transform = ::text)
        if (source.contains('\n')) return source.lines().joinToString("\n", transform = ::text)
        if (source.take(2) in listOf("✓ ", "! ", "— ")) return source.take(2) + text(source.drop(2))
        return source // SDK output, technical identifiers and unrecognized external errors remain verbatim.
    }
    companion object {
        internal val catalog: Map<String, List<String>> = checkNotNull(UiStrings::class.java.getResourceAsStream("/i18n/messages.tsv"))
            .bufferedReader(Charsets.UTF_8).useLines { lines -> lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
                val fields = line.split('\t').map { it.replace("\\n", "\n") }
                require(fields.size == 3 && fields.all { it.isNotEmpty() }) { "Invalid translation entry" }
                fields[0] to fields.drop(1)
            }.toList().also { require(it.map { entry -> entry.first }.distinct().size == it.size) { "Duplicate translation ID" } }.toMap() }
        private data class Template(val pattern: Regex, val translations: List<String>)
        private val templates = catalog.filterKeys { "{0}" in it }.map { (source, translations) ->
            val parts = Regex("\\{\\d+}").split(source)
            Template(Regex(parts.joinToString("(.*?)") { Regex.escape(it) }, RegexOption.DOT_MATCHES_ALL), translations)
        }
    }
}

val LocalUiStrings = staticCompositionLocalOf { UiStrings(AppLanguage.ENGLISH) }

/** Only authored copy / structured Hub messages. Render names, paths and SDK output with Text. */
@Composable
internal fun UiText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
                    fontSize: TextUnit = TextUnit.Unspecified, fontWeight: FontWeight? = null,
                    maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip,
                    style: TextStyle = androidx.compose.material3.LocalTextStyle.current) {
    Text(LocalUiStrings.current.text(text), modifier, color = color, fontSize = fontSize, fontWeight = fontWeight,
        maxLines = maxLines, overflow = overflow, style = style)
}
