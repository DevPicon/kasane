package dev.picon.androidhub.infrastructure

import dev.picon.androidhub.core.SessionFailure
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Owns only the subprocess it starts. Never answers yes to an SDK license prompt. */
internal class SdkToolProcess(private val executable: Path, private val environment: Map<String, String> = emptyMap()) {
    suspend fun run(arguments: List<String>, timeoutMs: Long = 120_000, onOutput: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        if (!Files.isExecutable(executable)) throw SessionFailure("Falta ${executable.fileName}. Instala Android SDK Command-line Tools (latest).")
        val log = Files.createTempFile("android-hub-sdk-", ".log")
        var process: Process? = null
        try {
            process = ProcessBuilder(listOf(executable.toString()) + arguments).apply {
                environment().putAll(this@SdkToolProcess.environment)
                redirectErrorStream(true)
                redirectOutput(log.toFile())
            }.start()
            process.outputStream.bufferedWriter().use { if (arguments.firstOrNull() == "--install") it.write("n\n") }
            var previous = ""
            val finished = withTimeoutOrNull(timeoutMs) {
                while (process.isAlive) {
                    val output = Files.readString(log)
                    if (output != previous) { previous = output; onOutput(output) }
                    delay(200)
                }
                true
            } ?: false
            if (!finished) throw SessionFailure("La herramienta del SDK superó el plazo. Puedes reintentar.")
            val output = Files.readString(log)
            onOutput(output)
            if (process.exitValue() != 0) {
                val detail = output.lineSequence().filter { it.contains("Error:", true) || it.contains("Warning:", true) }.lastOrNull().orEmpty().take(400)
                throw SessionFailure("La operación del SDK falló. $detail")
            }
            output
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                process?.let { owned ->
                    if (owned.isAlive) {
                        val children = owned.descendants().toList()
                        children.forEach { it.destroy() }
                        owned.destroy()
                        if (!owned.waitFor(2, TimeUnit.SECONDS)) owned.destroyForcibly().waitFor(2, TimeUnit.SECONDS)
                        children.filter { it.isAlive }.forEach { it.destroyForcibly() }
                    }
                }
                Files.deleteIfExists(log)
            }
        }
    }
}
