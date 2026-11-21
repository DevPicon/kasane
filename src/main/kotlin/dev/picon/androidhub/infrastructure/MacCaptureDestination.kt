package dev.picon.androidhub.infrastructure

import dev.picon.androidhub.core.CaptureDestination
import dev.picon.androidhub.core.CaptureKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal class MacCaptureDestination(private val title: (CaptureKind) -> String = { if (it == CaptureKind.VIDEO) "Save recording" else "Save screenshot" }) : CaptureDestination {
    override suspend fun choose(kind: CaptureKind): String? = withContext(Dispatchers.Swing) {
        val dialog = FileDialog(null as Frame?, title(kind), FileDialog.SAVE)
        try {
            dialog.file = "emulator-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.${kind.extension}"
            dialog.isVisible = true
            dialog.file?.let { name ->
                val filename = if (name.endsWith(".${kind.extension}", ignoreCase = true)) name else "$name.${kind.extension}"
                Path.of(dialog.directory, filename).toString()
            }
        } finally { dialog.dispose() }
    }
}
