package dev.picon.androidhub.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import dev.picon.androidhub.core.AppLanguage

internal fun ComposeContentTestRule.setSpanishContent(content: @Composable () -> Unit) {
    setContent { CompositionLocalProvider(LocalUiStrings provides UiStrings(AppLanguage.SPANISH), content = content) }
}
