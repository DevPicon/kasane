package dev.picon.androidhub.presentation

import dev.picon.androidhub.core.AppLanguage
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
internal fun LanguageSelector(language: AppLanguage, onSelect: (AppLanguage) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val strings = LocalUiStrings.current
    Box(modifier) {
        TextButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = strings.text("Idioma") }) {
            Text(language.displayName + " ▾")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            AppLanguage.entries.forEach { candidate ->
                DropdownMenuItem(text = { Text(candidate.displayName) }, onClick = { expanded = false; onSelect(candidate) })
            }
        }
    }
}
