package dev.picon.androidhub.infrastructure

import dev.picon.androidhub.core.CaptureFiles
import dev.picon.androidhub.core.SessionFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** Temp directories are private to the user. Only paths allocated by this owner may be removed. */
internal class MacCaptureFiles : CaptureFiles {
    private val owned = mutableSetOf<Path>()
    override suspend fun createVideo(): String = withContext(Dispatchers.IO) {
        val path = Files.createTempDirectory("kasane-video-").resolve("recording.mp4")
        synchronized(owned) { owned.add(path) }
        path.toString()
    }
    private fun checked(path: String): Path = Path.of(path).also {
        check(synchronized(owned) { it in owned }) { "Recording is not owned by this exporter" }
    }
    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) { Files.isRegularFile(checked(path)) }
    override suspend fun save(path: String, destination: String) = withContext(Dispatchers.IO) {
        val source = checked(path)
        val target = Path.of(destination).toAbsolutePath()
        if (target == source) throw SessionFailure("Elige una ubicación fuera de la carpeta temporal.")
        val staging = reserveDestination(destination)
        try {
            Files.copy(source, staging, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            publish(staging, target)
            discard(path)
        } finally { Files.deleteIfExists(staging) }
    }
    override suspend fun discard(path: String) = withContext(Dispatchers.IO) {
        val file = checked(path)
        Files.deleteIfExists(file)
        // Failure to remove an empty cache directory must not turn a completed save/discard into data loss.
        try { Files.deleteIfExists(file.parent) } catch (_: java.io.IOException) { }
        synchronized(owned) { owned.remove(file) }
        Unit
    }
}
