package dev.picon.androidhub.core

data class SystemImagePackage(val image: InstalledSystemImage, val installed: Boolean, val sizeBytes: Long? = null)
data class ImageDownloadProgress(val percent: Int? = null, val message: String = "Preparando descarga…")

interface SystemImageRepository {
    suspend fun list(): List<SystemImagePackage>
    suspend fun install(id: String, onProgress: (ImageDownloadProgress) -> Unit)
}
