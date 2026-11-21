package dev.picon.androidhub.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable
internal fun AboutDialog(icon: Painter, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFFF3F4F6),
        titleContentColor = Color(0xFF20252B),
        textContentColor = Color(0xFF5C626B),
        icon = { Image(icon, contentDescription = null, modifier = Modifier.size(72.dp)) },
        title = { Text(ProductInfo.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UiText("Versión ${ProductInfo.version} · Preview para macOS")
                UiText("Tus emuladores Android en un solo lugar. Cambia de dispositivo, controla sus posturas y comprueba su conexión.")
                UiText("Creado por Armando Picón\n@devpicon en todas las redes")
                UiText("Nombre clave: Kasane. Distribución privada de prueba.")
                TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF355D27)), onClick = { uriHandler.openUri("https://github.com/DevPicon/kasane") }) {
                    UiText("Repositorio y colaboración")
                }
                UiText("© 2026 Armando Picón. Los componentes de terceros conservan sus propias licencias.")
            }
        },
        confirmButton = { TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF355D27)), onClick = onDismiss) { UiText("Cerrar") } },
    )
}
