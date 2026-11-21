package dev.picon.androidhub.presentation

import dev.picon.androidhub.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CaptureState(
    val busy: Boolean = false,
    val recording: RunningEmulator? = null,
    val startedAtNanos: Long? = null,
    val stopping: Boolean = false,
    val showTaps: Boolean = true,
    val pendingVideo: String? = null,
    val savedPath: String? = null,
    val error: String? = null,
)

sealed interface CaptureAction {
    data class Screenshot(val target: RunningEmulator) : CaptureAction
    data class Record(val target: RunningEmulator) : CaptureAction
    data class ShowTaps(val enabled: Boolean) : CaptureAction
    data object Stop : CaptureAction
    data object Save : CaptureAction
    data object Discard : CaptureAction
    data class Finish(val target: RunningEmulator?, val completion: CompletableDeferred<Unit>) : CaptureAction
}

/** Window-owned actor; a cancelled save dialog never discards a finalized video. */
class CaptureController(private val capture: EmulatorCapture, private val destination: CaptureDestination,
                        private val files: CaptureFiles) {
    private val mutableState = MutableStateFlow(CaptureState())
    val state = mutableState.asStateFlow()
    private val actions = Channel<CaptureAction>(Channel.UNLIMITED)
    fun dispatch(action: CaptureAction) { actions.trySend(action) }

    suspend fun finishAndAwait(target: RunningEmulator? = null) {
        val completion = CompletableDeferred<Unit>()
        if (actions.trySend(CaptureAction.Finish(target, completion)).isSuccess) completion.await()
    }

    suspend fun run(): Unit = coroutineScope {
        var work: Job? = null
        var target: RunningEmulator? = null
        var stop: CompletableDeferred<Unit>? = null
        suspend fun savePending() {
            val video = mutableState.value.pendingVideo ?: return
            val path = destination.choose(CaptureKind.VIDEO) ?: return
            withContext(NonCancellable) {
                files.save(video, path)
                mutableState.update { it.copy(pendingVideo = null, savedPath = path) }
            }
        }
        fun failure(error: Exception) {
            mutableState.update { it.copy(error = if (error is SessionFailure) error.message else
                "No se pudo guardar. Revisa la conexión, los permisos y el espacio libre.") }
        }
        try {
            for (action in actions) when (action) {
                is CaptureAction.ShowTaps -> if (work?.isActive != true) mutableState.update { it.copy(showTaps = action.enabled) }
                CaptureAction.Stop -> if (stop != null && work?.isActive == true) {
                    mutableState.update { it.copy(stopping = true) }; stop.complete(Unit)
                }
                is CaptureAction.Finish -> {
                    if (action.target == null || action.target.instanceId == target?.instanceId) {
                        mutableState.update { if (it.recording == null) it else it.copy(stopping = true) }
                        stop?.complete(Unit)
                        work?.join()
                    }
                    if (action.target == null && mutableState.value.pendingVideo != null) {
                        val message = "Guarda o descarta la grabación pendiente antes de cerrar."
                        mutableState.update { it.copy(error = message) }
                        action.completion.completeExceptionally(PendingCaptureExport(message))
                    } else action.completion.complete(Unit)
                }
                CaptureAction.Save, CaptureAction.Discard -> {
                    if (work?.isActive == true || mutableState.value.pendingVideo == null) continue
                    mutableState.update { it.copy(busy = true, error = null) }
                    work = launch {
                        try {
                            if (action == CaptureAction.Save) savePending()
                            else {
                                files.discard(mutableState.value.pendingVideo!!)
                                mutableState.update { it.copy(pendingVideo = null) }
                            }
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { failure(error) }
                        finally { mutableState.update { it.copy(busy = false) } }
                    }
                }
                is CaptureAction.Record, is CaptureAction.Screenshot -> {
                    if (work?.isActive == true || mutableState.value.pendingVideo != null) continue
                    val video = action is CaptureAction.Record
                    val selected = if (action is CaptureAction.Record) action.target else (action as CaptureAction.Screenshot).target
                    target = selected
                    val stopSignal = CompletableDeferred<Unit>()
                    stop = if (video) stopSignal else null
                    val taps = mutableState.value.showTaps
                    mutableState.update { it.copy(busy = true, recording = if (video) selected else null,
                        startedAtNanos = null, stopping = false, savedPath = null, error = null) }
                    work = launch {
                        var temporary: String? = null
                        try {
                            if (video) {
                                temporary = files.createVideo()
                                capture.record(selected, temporary, taps, stopSignal) {
                                    mutableState.update { it.copy(startedAtNanos = System.nanoTime()) }
                                }
                                mutableState.update { it.copy(recording = null, startedAtNanos = null, pendingVideo = temporary, stopping = false) }
                                savePending()
                            } else {
                                destination.choose(CaptureKind.SCREENSHOT)?.let { path ->
                                    capture.screenshot(selected, path)
                                    mutableState.update { it.copy(savedPath = path) }
                                }
                            }
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { failure(error) }
                        finally {
                            withContext(NonCancellable) {
                                temporary?.let { path ->
                                    // A finalized file survives even if restoration/export failed or the window was disposed.
                                    if (mutableState.value.savedPath == null && files.exists(path))
                                        mutableState.update { it.copy(pendingVideo = path) }
                                    else if (mutableState.value.savedPath == null) files.discard(path)
                                }
                            }
                            mutableState.update { it.copy(busy = false, recording = null, startedAtNanos = null, stopping = false) }
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { work?.cancelAndJoin() }
            actions.close()
        }
    }
}
