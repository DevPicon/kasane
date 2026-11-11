package dev.picon.androidhub.core

data class AvdHardwareProfile(
    val id: String,
    val name: String,
    val manufacturer: String,
    val formFactor: FormFactor,
    val ramMb: Int = 2048,
    val storageGb: Int = 4,
    val minimumApi: Int = 1,
    val requiredTag: String? = null,
) {
    fun supports(image: InstalledSystemImage): Boolean = image.api >= minimumApi &&
        if (requiredTag != null) image.tag == requiredTag else image.tag in setOf("default", "google_apis", "google_apis_playstore")
}

data class InstalledSystemImage(val id: String, val label: String, val api: Int, val tag: String, val abi: String)
data class AvdCreationCatalog(val profiles: List<AvdHardwareProfile>, val images: List<InstalledSystemImage>, val existingNames: Set<String>)
data class AvdCreationRequest(val name: String, val profileId: String, val imageId: String, val ramMb: Int, val storageGb: Int) {
    fun validationError(catalog: AvdCreationCatalog): String? = when {
        !name.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")) -> "El nombre debe empezar por una letra y usar hasta 64 letras, números, guiones o guiones bajos."
        catalog.existingNames.any { it.equals(name, ignoreCase = true) } -> "Ya existe un AVD con ese nombre."
        ramMb !in 512..16384 -> "La RAM debe estar entre 512 y 16384 MB."
        storageGb !in 2..64 -> "El almacenamiento debe estar entre 2 y 64 GB."
        else -> {
            val profile = catalog.profiles.firstOrNull { it.id == profileId }
            val image = catalog.images.firstOrNull { it.id == imageId }
            when {
                profile == null -> "El perfil ya no está disponible en el CLI instalado."
                image == null -> "La imagen ya no está instalada o no es compatible con este equipo."
                !profile.supports(image) -> "La imagen no es compatible con el perfil seleccionado."
                else -> null
            }
        }
    }
}

interface AvdCreator {
    suspend fun catalog(): AvdCreationCatalog
    suspend fun create(request: AvdCreationRequest): String
}
