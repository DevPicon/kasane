package dev.picon.androidhub.infrastructure

import dev.picon.androidhub.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

class MacSystemImageRepository(
    private val sdk: Path = Path.of(System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME") ?: "${System.getProperty("user.home")}/Library/Android/sdk"),
    private val hostAbi: String = if (System.getProperty("os.arch") in setOf("arm64", "aarch64")) "arm64-v8a" else "x86_64",
    private val usableSpace: (Path) -> Long = { Files.getFileStore(it).usableSpace },
) : SystemImageRepository {
    private val tool get() = SdkToolProcess(sdk.resolve("cmdline-tools/latest/bin/sdkmanager"))
    override suspend fun list(): List<SystemImagePackage> = withContext(Dispatchers.IO) {
        val output = tool.run(listOf("--list", "--channel=0", "--sdk_root=$sdk"))
        val parsed = parseSystemImagePackages(output, hostAbi)
        if (output.contains("Failed to download any source lists", ignoreCase = true) || (parsed.isEmpty() && output.contains("Failed to", ignoreCase = true))) {
            throw SessionFailure("No se pudo consultar el catálogo. Revisa la conexión y reintenta.")
        }
        parsed.map { it.copy(installed = installed(it.image.id)) }
    }

    override suspend fun install(id: String, onProgress: (ImageDownloadProgress) -> Unit): Unit = withContext(Dispatchers.IO) {
        val selected = list().firstOrNull { it.image.id == id }
            ?: throw SessionFailure("La imagen ya no está disponible para este equipo. Recarga el catálogo.")
        if (selected.installed) return@withContext
        if (usableSpace(sdk) < 6L * 1024 * 1024 * 1024) throw SessionFailure("Se requieren al menos 6 GB libres para descargar y extraer la imagen. El espacio final depende del paquete.")
        onProgress(ImageDownloadProgress(message = "Conectando con el repositorio de Android…"))
        var licensePending = false
        val output = try {
            tool.run(listOf("--install", id, "--channel=0", "--sdk_root=$sdk"), timeoutMs = 30 * 60_000,
                onOutput = {
                    if (it.contains("license", true) && (it.contains("not accepted", true) || it.contains("Accept?", true))) licensePending = true
                    onProgress(parseImageDownloadProgress(it))
                })
        } catch (error: SessionFailure) {
            if (licensePending) throw SessionFailure(licenseHelp)
            throw error
        }
        if (!installed(id)) {
            if (output.contains("license", true)) throw SessionFailure(licenseHelp)
            throw SessionFailure("La herramienta terminó sin instalar una imagen válida. Revisa la conexión y reintenta.")
        }
        onProgress(ImageDownloadProgress(100, "Imagen instalada."))
    }

    private val licenseHelp = "La imagen requiere una licencia pendiente. Revísala y acéptala, si estás de acuerdo, con sdkmanager --licenses en tu SDK; después reintenta. El Hub no acepta licencias automáticamente."

    private fun installed(id: String): Boolean {
        val directory = id.split(';').fold(sdk) { path, part -> path.resolve(part) }
        return Files.isRegularFile(directory.resolve("system.img")) && Files.isRegularFile(directory.resolve("source.properties"))
    }
}

internal fun parseSystemImagePackages(output: String, hostAbi: String): List<SystemImagePackage> {
    var section = ""
    val packages = mutableListOf<SystemImagePackage>()
    output.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line in setOf("Installed packages:", "Available Packages:", "Available Updates:")) { section = line; return@forEach }
        if (section !in setOf("Installed packages:", "Available Packages:")) return@forEach
        val columns = line.split('|').map { it.trim() }
        val id = columns.firstOrNull() ?: return@forEach
        val parts = id.split(';')
        if (columns.size < 3 || parts.size != 4 || parts[0] != "system-images" || parts[3] != hostAbi ||
            !parts[1].matches(Regex("android-\\d+(?:\\.\\d+)?")) || !parts[2].matches(Regex("[A-Za-z0-9_-]+"))) return@forEach
        val api = parts[1].removePrefix("android-")
        val image = InstalledSystemImage(id, "API $api · ${columns[2]} · $hostAbi", api.substringBefore('.').toInt(), parts[2], hostAbi)
        packages += SystemImagePackage(image, section == "Installed packages:")
    }
    return packages.distinctBy { it.image.id }.sortedWith(compareByDescending<SystemImagePackage> { it.image.api }.thenBy { it.image.label })
}

internal fun parseImageDownloadProgress(output: String): ImageDownloadProgress {
    val line = output.replace('\r', '\n').lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
    val percent = Regex("(\\d{1,3})%").findAll(output).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
    val message = line.replace(Regex("^\\[[^]]*]\\s*"), "").trim().take(180).ifEmpty { "Descargando imagen…" }
    return ImageDownloadProgress(percent, message)
}
