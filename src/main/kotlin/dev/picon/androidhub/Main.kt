package dev.picon.androidhub

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import java.awt.Desktop

fun main() = application {
    val images = remember { SystemImagesController(MacSystemImageRepository()) }
    val creation = remember { AvdCreationController(MacAvdCreator()) }
    val controller = remember { HubController(MacEmulatorInventory(MacEmulatorDiscovery()), MacTransportDiagnostics()) { images.cancelAndAwait(); creation.cancelAndAwait() } }
    val reduceMotion by produceState(false) { value = readReduceMotion() }
    DisposableEffect(controller) {
        val desktop = Desktop.getDesktop()
        val supported = desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)
        if (supported) desktop.setQuitHandler { _, response ->
            response.cancelQuit()
            controller.dispatch(HubAction.Close)
        }
        onDispose { if (supported) desktop.setQuitHandler(null) }
    }
    Window(
        onCloseRequest = { controller.dispatch(HubAction.Close) },
        title = "Kasane",
        state = rememberWindowState(width = 1200.dp, height = 800.dp),
    ) {
        LaunchedEffect(controller) { controller.run() }
        LaunchedEffect(creation) { creation.run { controller.dispatch(HubAction.Created(it)) } }
        LaunchedEffect(images) { images.run { creation.dispatch(AvdCreationAction.RefreshImages(it)) } }
        val imageState by images.state.collectAsState()
        val state by controller.state.collectAsState()
        LaunchedEffect(state.closed) { if (state.closed) exitApplication() }
        val creationState by creation.state.collectAsState()
        HubScreen(state, controller.frame, controller::dispatch, reduceMotion, onCreate = { creation.dispatch(AvdCreationAction.Open) })
        AvdCreationDialog(creationState, creation::dispatch, onManageImages = {
            images.dispatch(SystemImagesAction.Open(creationState.catalog?.profiles?.firstOrNull { it.id == creationState.profileId }))
        })
        SystemImagesDialog(imageState, images::dispatch)
    }
}
