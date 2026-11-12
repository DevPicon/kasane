package dev.picon.androidhub

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.picon.androidhub.infrastructure.MacTransportDiagnostics
import dev.picon.androidhub.infrastructure.MacEmulatorDiscovery
import dev.picon.androidhub.infrastructure.MacEmulatorInventory
import dev.picon.androidhub.infrastructure.readReduceMotion
import dev.picon.androidhub.presentation.HubController
import dev.picon.androidhub.presentation.HubScreen
import dev.picon.androidhub.presentation.HubAction
import dev.picon.androidhub.presentation.AvdCreationController
import dev.picon.androidhub.presentation.AvdCreationDialog
import dev.picon.androidhub.presentation.AvdCreationAction
import dev.picon.androidhub.infrastructure.MacAvdCreator
import dev.picon.androidhub.infrastructure.MacSystemImageRepository
import dev.picon.androidhub.presentation.SystemImagesController
import dev.picon.androidhub.presentation.SystemImagesAction
import dev.picon.androidhub.presentation.SystemImagesDialog
import dev.picon.androidhub.presentation.ProductInfo
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.awt.Desktop
import java.awt.EventQueue
import androidx.compose.ui.window.MenuBar
import dev.picon.androidhub.presentation.AboutDialog
import dev.picon.androidhub.infrastructure.configureDockIcon
import dev.picon.androidhub.infrastructure.MacEmulatorCapture
import dev.picon.androidhub.infrastructure.MacCaptureDestination
import dev.picon.androidhub.presentation.CaptureController

fun main() {
    configureDockIcon()
    launchHub()
}

private fun launchHub() = application {
    val appIcon = remember {
        val bytes = checkNotNull(ProductInfo::class.java.getResourceAsStream("/hub-icon.png")).use { it.readBytes() }
        BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
    }
    var showAbout by remember { mutableStateOf(false) }
    val images = remember { SystemImagesController(MacSystemImageRepository()) }
    val creation = remember { AvdCreationController(MacAvdCreator()) }
    val capture = remember { CaptureController(MacEmulatorCapture(), MacCaptureDestination()) }
    val controller = remember { HubController(MacEmulatorInventory(MacEmulatorDiscovery()), MacTransportDiagnostics(),
        beforePowerOff = { capture.finishAndAwait(it) },
        allowGeometryChange = { capture.state.value.recording?.instanceId != it.instanceId }) {
        capture.finishAndAwait(); images.cancelAndAwait(); creation.cancelAndAwait()
    } }
    val reduceMotion by produceState(false) { value = readReduceMotion() }
    DisposableEffect(controller) {
        val desktop = Desktop.getDesktop()
        val supported = desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)
        if (supported) desktop.setQuitHandler { _, response ->
            response.cancelQuit()
            controller.dispatch(HubAction.Close)
        }
        val aboutSupported = desktop.isSupported(Desktop.Action.APP_ABOUT)
        if (aboutSupported) desktop.setAboutHandler { EventQueue.invokeLater { showAbout = true } }
        onDispose {
            if (supported) desktop.setQuitHandler(null)
            if (aboutSupported) desktop.setAboutHandler(null)
        }
    }
    Window(
        onCloseRequest = { controller.dispatch(HubAction.Close) },
        title = ProductInfo.displayName,
        icon = appIcon,
        state = rememberWindowState(width = 1200.dp, height = 800.dp),
    ) {
        MenuBar {
            Menu("Ayuda") { Item("Acerca de…", onClick = { showAbout = true }) }
        }
        if (showAbout) AboutDialog(appIcon, onDismiss = { showAbout = false })
        LaunchedEffect(capture) { capture.run() }
        val captureState by capture.state.collectAsState()
        LaunchedEffect(controller) { controller.run() }
        LaunchedEffect(creation) { creation.run { controller.dispatch(HubAction.Created(it)) } }
        LaunchedEffect(images) { images.run { creation.dispatch(AvdCreationAction.RefreshImages(it)) } }
        val imageState by images.state.collectAsState()
        val state by controller.state.collectAsState()
        LaunchedEffect(state.closed) { if (state.closed) exitApplication() }
        val creationState by creation.state.collectAsState()
        HubScreen(state, controller.frame, controller::dispatch, reduceMotion, onCreate = { creation.dispatch(AvdCreationAction.Open) },
            captureState = captureState, onCapture = capture::dispatch)
        AvdCreationDialog(creationState, creation::dispatch, onManageImages = {
            images.dispatch(SystemImagesAction.Open(creationState.catalog?.profiles?.firstOrNull { it.id == creationState.profileId }))
        })
        SystemImagesDialog(imageState, images::dispatch)
    }
}
