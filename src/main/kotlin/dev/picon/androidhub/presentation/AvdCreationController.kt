package dev.picon.androidhub.presentation

import dev.picon.androidhub.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AvdCreationState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val creating: Boolean = false,
    val catalog: AvdCreationCatalog? = null,
    val name: String = "",
    val profileId: String = "",
    val imageId: String = "",
    val ram: String = "2048",
    val storage: String = "4",
    val error: String? = null,
    val customName: Boolean = false,
) {
    fun request(): AvdCreationRequest? {
        val memory = ram.toIntOrNull() ?: return null
        val disk = storage.toIntOrNull() ?: return null
        return AvdCreationRequest(name, profileId, imageId, memory, disk)
    }
    val validation: String? get() = catalog?.let { available ->
        val request = request()
        if (request == null) "RAM y almacenamiento deben ser números enteros." else request.validationError(available)
    }
    val canCreate: Boolean get() = visible && !loading && !creating && catalog != null && validation == null
}

sealed interface AvdCreationAction {
    data object Open : AvdCreationAction
    data class RefreshImages(val preferredImageId: String? = null) : AvdCreationAction
    data class Refreshed(val generation: Long, val catalog: AvdCreationCatalog, val preferredImageId: String?) : AvdCreationAction
    data object Cancel : AvdCreationAction
    data object Submit : AvdCreationAction
    data class Name(val value: String) : AvdCreationAction
    data class Profile(val id: String) : AvdCreationAction
    data class Image(val id: String) : AvdCreationAction
    data class Ram(val value: String) : AvdCreationAction
    data class Storage(val value: String) : AvdCreationAction
    data class Loaded(val generation: Long, val catalog: AvdCreationCatalog) : AvdCreationAction
    data class Failed(val generation: Long, val message: String) : AvdCreationAction
    data class Created(val generation: Long, val id: String) : AvdCreationAction
    data class Stop(val completion: CompletableDeferred<Unit>) : AvdCreationAction
}

class AvdCreationController(private val creator: AvdCreator) {
    private val mutableState = MutableStateFlow(AvdCreationState())
    val state = mutableState.asStateFlow()
    private val actions = Channel<AvdCreationAction>(Channel.UNLIMITED)
    fun dispatch(action: AvdCreationAction) { actions.trySend(action) }

    suspend fun cancelAndAwait() {
        val completion = CompletableDeferred<Unit>()
        if (actions.trySend(AvdCreationAction.Stop(completion)).isSuccess) completion.await()
    }

    suspend fun run(onCreated: (String) -> Unit): Unit = coroutineScope {
        var work: Job? = null
        var generation = 0L
        suspend fun cancel() {
            generation++
            work?.cancelAndJoin()
            work = null
            mutableState.update { it.copy(visible = false, loading = false, creating = false) }
        }
        try {
            for (action in actions) {
                when (action) {
                    AvdCreationAction.Open -> {
                        if (mutableState.value.creating) continue
                        work?.cancelAndJoin()
                        val token = ++generation
                        mutableState.value = AvdCreationState(visible = true, loading = true)
                        work = launch {
                            try { dispatch(AvdCreationAction.Loaded(token, creator.catalog())) }
                            catch (error: CancellationException) { throw error }
                            catch (error: Exception) { dispatch(AvdCreationAction.Failed(token, creationMessage(error))) }
                        }
                    }
                    is AvdCreationAction.RefreshImages -> {
                        if (!mutableState.value.visible || mutableState.value.creating) continue
                        work?.cancelAndJoin()
                        val token = ++generation
                        mutableState.update { it.copy(loading = true, error = null) }
                        work = launch {
                            try { dispatch(AvdCreationAction.Refreshed(token, creator.catalog(), action.preferredImageId)) }
                            catch (error: CancellationException) { throw error }
                            catch (error: Exception) { dispatch(AvdCreationAction.Failed(token, creationMessage(error))) }
                        }
                    }
                    is AvdCreationAction.Refreshed -> if (action.generation == generation) {
                        mutableState.update { current ->
                            val profile = action.catalog.profiles.firstOrNull { it.id == current.profileId }
                                ?: action.catalog.profiles.firstOrNull { it.id == "medium_phone" } ?: action.catalog.profiles.firstOrNull()
                            val compatible = action.catalog.images.filter { profile?.supports(it) == true }
                            val image = compatible.firstOrNull { it.id == action.preferredImageId }
                                ?: compatible.firstOrNull { it.id == current.imageId } ?: compatible.firstOrNull()
                            current.copy(loading = false, catalog = action.catalog, profileId = profile?.id.orEmpty(), imageId = image?.id.orEmpty(),
                                name = if (current.customName) current.name else suggestedName(action.catalog, profile, image),
                                ram = if (current.profileId == profile?.id) current.ram else (profile?.ramMb ?: 2048).toString(),
                                storage = if (current.profileId == profile?.id) current.storage else (profile?.storageGb ?: 4).toString())
                        }
                    }
                    AvdCreationAction.Cancel -> cancel()
                    is AvdCreationAction.Stop -> { cancel(); action.completion.complete(Unit) }
                    is AvdCreationAction.Loaded -> if (action.generation == generation) {
                        val profile = action.catalog.profiles.firstOrNull { it.id == "medium_phone" } ?: action.catalog.profiles.firstOrNull()
                        val image = action.catalog.images.firstOrNull { candidate -> profile?.supports(candidate) == true }
                        mutableState.update { it.copy(loading = false, catalog = action.catalog, profileId = profile?.id.orEmpty(),
                            name = suggestedName(action.catalog, profile, image), imageId = image?.id.orEmpty(),
                            ram = (profile?.ramMb ?: 2048).toString(), storage = (profile?.storageGb ?: 4).toString()) }
                    }
                    is AvdCreationAction.Failed -> if (action.generation == generation) {
                        mutableState.update { it.copy(loading = false, creating = false, error = action.message) }
                    }
                    is AvdCreationAction.Created -> if (action.generation == generation) {
                        mutableState.update { it.copy(visible = false, creating = false) }
                        onCreated(action.id)
                    }
                    AvdCreationAction.Submit -> {
                        val current = mutableState.value
                        if (!current.canCreate) continue
                        val request = current.request() ?: continue
                        val token = ++generation
                        mutableState.update { it.copy(creating = true, error = null) }
                        work = launch {
                            try { dispatch(AvdCreationAction.Created(token, creator.create(request))) }
                            catch (error: CancellationException) { throw error }
                            catch (error: Exception) { dispatch(AvdCreationAction.Failed(token, creationMessage(error))) }
                        }
                    }
                    else -> if (!mutableState.value.creating && !mutableState.value.loading) {
                        mutableState.update { current ->
                            when (action) {
                                is AvdCreationAction.Name -> current.copy(name = action.value, customName = true, error = null)
                                is AvdCreationAction.Ram -> current.copy(ram = action.value, error = null)
                                is AvdCreationAction.Storage -> current.copy(storage = action.value, error = null)
                                is AvdCreationAction.Image -> current.copy(imageId = action.id, error = null,
                                    name = if (current.customName) current.name else suggestedName(current.catalog,
                                        current.catalog?.profiles?.firstOrNull { it.id == current.profileId }, current.catalog?.images?.firstOrNull { it.id == action.id }))
                                is AvdCreationAction.Profile -> {
                                    val profile = current.catalog?.profiles?.firstOrNull { it.id == action.id }
                                    val image = current.catalog?.images?.firstOrNull { profile?.supports(it) == true }
                                    current.copy(profileId = action.id, imageId = image?.id.orEmpty(),
                                        name = if (current.customName) current.name else suggestedName(current.catalog, profile, image),
                                        ram = (profile?.ramMb ?: 2048).toString(), storage = (profile?.storageGb ?: 4).toString(), error = null)
                                }
                            }
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

private fun suggestedName(catalog: AvdCreationCatalog?, profile: AvdHardwareProfile?, image: InstalledSystemImage?): String {
    if (catalog == null || profile == null || image == null) return ""
    val api = image.id.split(';').getOrNull(1)?.removePrefix("android-") ?: image.api.toString()
    val base = (profile.id + "_API_" + api).replace(Regex("[^A-Za-z0-9_-]"), "_").let {
        if (it.firstOrNull()?.isLetter() == true) it else "AVD_$it"
    }.take(58)
    return generateSequence(0) { it + 1 }.map { if (it == 0) base else "${base}_$it" }
        .first { candidate -> catalog.existingNames.none { it.equals(candidate, ignoreCase = true) } }
}

private fun creationMessage(error: Exception) = when (error) {
    is SessionFailure -> error.message ?: "No se pudo crear el AVD."
    is java.io.IOException -> "No se pudo crear el AVD. Revisa los permisos y el espacio disponible en el SDK y la carpeta de AVD."
    else -> "No se pudo crear el AVD (${error.javaClass.simpleName})."
}
