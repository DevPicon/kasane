package dev.picon.androidhub.infrastructure

import java.awt.Taskbar
import javax.imageio.ImageIO

/** Window.icon does not set the macOS Dock icon when running the launcher directly. */
internal fun configureDockIcon() {
    if (!Taskbar.isTaskbarSupported()) return
    val taskbar = Taskbar.getTaskbar()
    if (!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) return
    val icon = checkNotNull(DockIconResource::class.java.getResourceAsStream("/hub-icon.png")) { "Missing application icon" }
        .use { ImageIO.read(it) }
    taskbar.iconImage = checkNotNull(icon) { "Invalid application icon" }
}

private object DockIconResource
