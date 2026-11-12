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
    val savedPath: String? = null,
    val error: String? = null,
)

sealed interface CaptureAction {
    data class Screenshot(val target: RunningEmulator) : CaptureAction
    data class Record(val target: RunningEmulator) : CaptureAction
    data class ShowTaps(val enabled: Boolean) : CaptureAction
    data object Stop : CaptureAction
    data class Finish(val target: RunningEmulator?, val completion: CompletableDeferred<Unit>) : CaptureAction
}

/** Window-owned actor; work never outlives run(). Selection never redirects an active export. */
class CaptureController(private val capture: EmulatorCapture, private val destination: CaptureDestination) {
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
        try {
            for (action in actions) when (action) {
                is CaptureAction.ShowTaps -> if (work?.isActive != true) mutableState.update { it.copy(showTaps = action.enabled) }
                CaptureAction.Stop -> {
                    if (stop != null && work?.isActive == true) {
                        mutableState.update { it.copy(stopping = true) }
                        stop.complete(Unit)
                    }
                }
                is CaptureAction.Finish -> {
                    if (action.target == null || action.target.instanceId == target?.instanceId) {
                        mutableState.update { if (it.recording == null) it else it.copy(stopping = true) }
                        stop?.complete(Unit)
                        work?.join()
                    }
                    action.completion.complete(Unit)
                }
                is CaptureAction.Record, is CaptureAction.Screenshot -> {
                    if (work?.isActive == true) continue
                    val video = action is CaptureAction.Record
                    val selected = if (action is CaptureAction.Record) action.target else (action as CaptureAction.Screenshot).target
                    target = selected
                    val stopSignal = CompletableDeferred<Unit>()
                    stop = if (video) stopSignal else null
                    val taps = mutableState.value.showTaps
                    mutableState.update { it.copy(busy = true, recording = if (video) selected else null,
                        startedAtNanos = null, stopping = false, savedPath = null, error = null) }
                    work = launch {
                        try {
                            val path = destination.choose(if (video) CaptureKind.VIDEO else CaptureKind.SCREENSHOT)
                            if (path != null && !stopSignal.isCompleted) {
                                if (video) capture.record(selected, path, taps, stopSignal) {
                                    mutableState.update { it.copy(startedAtNanos = System.nanoTime()) }
                                } else capture.screenshot(selected, path)
                                mutableState.update { it.copy(savedPath = path) }
                            }
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) {
                            mutableState.update { it.copy(error = if (error is SessionFailure) error.message else "No se pudo guardar. Revisa la conexión, los permisos y el espacio libre.") }
                        } finally {
                            mutableState.update { it.copy(busy = false, recording = null, startedAtNanos = null, stopping = false) }
                        }
                    }
                }
            }
        } finally {
            // Unexpected disposal still releases the owned recorder and temporary Android settings.
            withContext(NonCancellable) { work?.cancelAndJoin() }
            actions.close()
        }
    }
}
