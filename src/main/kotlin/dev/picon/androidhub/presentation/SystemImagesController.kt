package dev.picon.androidhub.presentation

import dev.picon.androidhub.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SystemImagesState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val installing: Boolean = false,
    val packages: List<SystemImagePackage> = emptyList(),
    val selectedId: String? = null,
    val profile: AvdHardwareProfile? = null,
    val progress: ImageDownloadProgress = ImageDownloadProgress(),
    val error: String? = null,
    val installedId: String? = null,
) {
    val compatible get() = packages.filter { profile?.supports(it.image) != false }
    val canInstall get() = visible && !loading && !installing && compatible.any { it.image.id == selectedId && !it.installed }
}

sealed interface SystemImagesAction {
    data class Open(val profile: AvdHardwareProfile? = null) : SystemImagesAction
    data object Cancel : SystemImagesAction
    data object Refresh : SystemImagesAction
    data class Select(val id: String) : SystemImagesAction
    data object Install : SystemImagesAction
    data class Loaded(val token: Long, val packages: List<SystemImagePackage>) : SystemImagesAction
    data class Progress(val token: Long, val value: ImageDownloadProgress) : SystemImagesAction
    data class Installed(val token: Long, val id: String) : SystemImagesAction
    data class Failed(val token: Long, val message: String) : SystemImagesAction
    data class Stop(val completion: CompletableDeferred<Unit>) : SystemImagesAction
}

class SystemImagesController(private val repository: SystemImageRepository) {
    private val mutableState = MutableStateFlow(SystemImagesState())
    val state = mutableState.asStateFlow()
    private val actions = Channel<SystemImagesAction>(Channel.UNLIMITED)
    fun dispatch(action: SystemImagesAction) { actions.trySend(action) }
    suspend fun cancelAndAwait() {
        val completion = CompletableDeferred<Unit>()
        if (actions.trySend(SystemImagesAction.Stop(completion)).isSuccess) completion.await()
    }

    suspend fun run(onInstalled: (String) -> Unit): Unit = coroutineScope {
        var work: Job? = null
        var token = 0L
        suspend fun cancel() {
            token++
            work?.cancelAndJoin()
            work = null
            mutableState.value = mutableState.value.copy(visible = false, loading = false, installing = false)
        }
        fun load() {
            val generation = ++token
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            work = launch {
                try { dispatch(SystemImagesAction.Loaded(generation, repository.list())) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { dispatch(SystemImagesAction.Failed(generation, imageError(error))) }
            }
        }
        try {
            for (action in actions) when (action) {
                is SystemImagesAction.Open -> {
                    if (mutableState.value.installing) continue
                    work?.cancelAndJoin()
                    mutableState.value = SystemImagesState(visible = true, profile = action.profile)
                    load()
                }
                SystemImagesAction.Refresh -> if (!mutableState.value.loading && !mutableState.value.installing && mutableState.value.visible) load()
                SystemImagesAction.Cancel -> cancel()
                is SystemImagesAction.Stop -> { cancel(); action.completion.complete(Unit) }
                is SystemImagesAction.Loaded -> if (action.token == token) {
                    val current = mutableState.value.copy(loading = false, packages = action.packages)
                    mutableState.value = current.copy(selectedId = current.selectedId?.takeIf { id -> current.compatible.any { it.image.id == id && !it.installed } }
                        ?: current.compatible.firstOrNull { !it.installed }?.image?.id)
                }
                is SystemImagesAction.Select -> if (!mutableState.value.installing && mutableState.value.compatible.any { it.image.id == action.id && !it.installed }) {
                    mutableState.value = mutableState.value.copy(selectedId = action.id, error = null, installedId = null)
                }
                SystemImagesAction.Install -> {
                    val current = mutableState.value
                    if (!current.canInstall) continue
                    val id = current.selectedId ?: continue
                    val generation = ++token
                    mutableState.value = current.copy(installing = true, error = null, installedId = null, progress = ImageDownloadProgress())
                    work = launch {
                        try {
                            repository.install(id) { dispatch(SystemImagesAction.Progress(generation, it)) }
                            dispatch(SystemImagesAction.Installed(generation, id))
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { dispatch(SystemImagesAction.Failed(generation, imageError(error))) }
                    }
                }
                is SystemImagesAction.Progress -> if (action.token == token) mutableState.value = mutableState.value.copy(progress = action.value)
                is SystemImagesAction.Failed -> if (action.token == token) mutableState.value = mutableState.value.copy(loading = false, installing = false, error = action.message)
                is SystemImagesAction.Installed -> if (action.token == token) {
                    mutableState.value = mutableState.value.copy(installing = false, installedId = action.id, selectedId = null,
                        packages = mutableState.value.packages.map { if (it.image.id == action.id) it.copy(installed = true) else it })
                    onInstalled(action.id)
                }
            }
        } finally {
            withContext(NonCancellable) { work?.cancelAndJoin() }
            actions.close()
        }
    }
}

private fun imageError(error: Exception): String = when (error) {
    is SessionFailure -> error.message ?: "No se pudo instalar la imagen."
    is java.io.IOException -> "No se pudo acceder al SDK o al repositorio. Revisa conexión, permisos y espacio libre."
    else -> "No se pudo completar la operación (${error.javaClass.simpleName})."
}
