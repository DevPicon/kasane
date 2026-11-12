package dev.picon.androidhub.infrastructure

import dev.picon.androidhub.core.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** ADB is scoped to a verified emulator instance. Remote names and PIDs are generated/validated here. */
internal class MacEmulatorCapture(
    sdk: Path = Path.of(System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME") ?: "${System.getProperty("user.home")}/Library/Android/sdk"),
    private val instances: suspend () -> List<RunningEmulator> = { MacEmulatorDiscovery().discover() },
) : EmulatorCapture {
    private val adb = sdk.resolve("platform-tools/adb")

    private suspend fun verify(target: RunningEmulator) {
        if (instances().none { it.serial == target.serial && it.instanceId == target.instanceId })
            throw SessionFailure("La instancia cambió o se desconectó. Actualiza los emuladores.")
    }

    private suspend fun command(target: RunningEmulator, vararg args: String): String {
        verify(target)
        return SdkToolProcess(adb).run(listOf("-s", target.serial) + args, timeoutMs = 15_000)
    }

    override suspend fun screenshot(emulator: RunningEmulator, destination: String) = withContext(Dispatchers.IO) {
        val local = reserveDestination(destination)
        val remote = "/data/local/tmp/kasane-${UUID.randomUUID()}.png"
        try {
            command(emulator, "shell", "screencap", "-p", remote)
            command(emulator, "pull", remote, local.toString())
            if (ImageIO.read(local.toFile()) == null) throw SessionFailure("Android no devolvió una captura PNG válida.")
            currentCoroutineContext().ensureActive()
            publish(local, Path.of(destination))
        } finally {
            withContext(NonCancellable) {
                try { command(emulator, "shell", "rm", "-f", remote) } catch (_: Exception) { /* Own temporary only; instance may be gone. */ }
                Files.deleteIfExists(local)
            }
        }
    }

    override suspend fun record(emulator: RunningEmulator, destination: String, showTaps: Boolean,
                                stop: Deferred<Unit>, onStarted: () -> Unit) = withContext(Dispatchers.IO) {
        val local = reserveDestination(destination)
        val remote = "/data/local/tmp/kasane-${UUID.randomUUID()}.mp4"
        val log = Files.createTempFile("kasane-record-", ".log")
        var process: Process? = null
        var pid: Long? = null
        var originalTaps: String? = null
        var restoreTaps = false
        val requestedTaps = if (showTaps) "1" else "0"
        try {
            verify(emulator)
            command(emulator, "shell", "screenrecord", "--help")
            originalTaps = command(emulator, "shell", "settings", "get", "system", "show_touches").trim()
            if (originalTaps != requestedTaps) {
                if (originalTaps !in setOf("0", "1", "null")) throw SessionFailure("No se pudo leer la preferencia de taps de Android.")
                // Set before awaiting a cancellable command so cleanup also covers cancellation in flight.
                restoreTaps = true
                command(emulator, "shell", "settings", "put", "system", "show_touches", requestedTaps)
            }
            verify(emulator)
            // The shell is replaced by screenrecord: PID belongs only to this unique remote file.
            process = ProcessBuilder(adb.toString(), "-s", emulator.serial, "shell", "sh", "-c",
                "'echo \$\$; exec screenrecord --time-limit 180 $remote'")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start()
            process.outputStream.close()
            val receivedPid = withTimeoutOrNull(5000) {
                while (pid == null) {
                    pid = Files.readString(log).lineSequence().firstOrNull()?.trim()?.toLongOrNull()?.takeIf { it > 1 }
                    if (!process.isAlive && pid == null) throw SessionFailure("Este emulador no pudo iniciar screenrecord.")
                    if (pid == null) delay(50)
                }
                true
            } ?: false
            if (!receivedPid) throw SessionFailure("Android no confirmó el inicio de la grabación dentro del plazo.")
            delay(400)
            if (!process.isAlive) throw SessionFailure("Android rechazó la grabación. Este display puede no ser compatible.")
            onStarted()
            // Android ends automatically after 180 seconds. ADB exit/disconnect is also observed.
            val finished = withTimeoutOrNull(190_000) {
                while (process.isAlive && !stop.isCompleted) delay(100)
                if (process.isAlive) try { signalOwned(emulator, pid!!, remote, "2") }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { if (process.isAlive) throw error }
                while (process.isAlive) delay(100)
                true
            } ?: false
            if (!finished) throw SessionFailure("Android no finalizó la grabación dentro del plazo.")
            if (process.exitValue() != 0) throw SessionFailure("La grabación se interrumpió; no se guardó un video válido.")
            command(emulator, "pull", remote, local.toString())
            if (!isMp4(local)) throw SessionFailure("Android no produjo un archivo MP4 válido.")
            currentCoroutineContext().ensureActive()
            publish(local, Path.of(destination))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    val ownedPid = pid
                    if (process?.isAlive == true && ownedPid != null) {
                        try { signalOwned(emulator, ownedPid, remote, "2") } catch (_: Exception) { }
                        if (!process.waitFor(5, TimeUnit.SECONDS)) {
                            try { signalOwned(emulator, ownedPid, remote, "15") } catch (_: Exception) { }
                        }
                    }
                    if (process?.isAlive == true) {
                        process.destroy()
                        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                    }
                    if (restoreTaps) {
                        try {
                            if (command(emulator, "shell", "settings", "get", "system", "show_touches").trim() == requestedTaps) {
                                if (originalTaps == "null") command(emulator, "shell", "settings", "delete", "system", "show_touches")
                                else command(emulator, "shell", "settings", "put", "system", "show_touches", originalTaps!!)
                            }
                        } catch (_: Exception) {
                            throw SessionFailure("No se pudo restaurar Mostrar taps. Revisa Opciones de desarrollador en ese AVD.")
                        }
                    }
                } finally {
                    try { command(emulator, "shell", "rm", "-f", remote) } catch (_: Exception) { }
                    Files.deleteIfExists(local)
                    Files.deleteIfExists(log)
                }
            }
        }
    }

    private suspend fun signalOwned(target: RunningEmulator, pid: Long, remote: String, signal: String) {
        val cmdline = command(target, "shell", "cat", "/proc/$pid/cmdline")
        if (!ownsRecording(cmdline, remote)) throw SessionFailure("No se pudo verificar el proceso de grabación propio.")
        command(target, "shell", "kill", "-$signal", pid.toString())
    }
}

internal fun ownsRecording(cmdline: String, remote: String): Boolean {
    val args = cmdline.split('\u0000').filter { it.isNotEmpty() }
    return args.firstOrNull()?.substringAfterLast('/') == "screenrecord" && args.lastOrNull() == remote
}

internal fun reserveDestination(destination: String): Path {
    val target = Path.of(destination).toAbsolutePath()
    if (Files.exists(target)) throw SessionFailure("El archivo ya existe. Elige un nombre nuevo para conservar el original.")
    val parent = target.parent
    if (!Files.isDirectory(parent)) throw SessionFailure("La carpeta de destino no está disponible.")
    return Files.createTempFile(parent, ".kasane-", ".partial")
}

internal fun publish(temporary: Path, destination: Path) {
    // No REPLACE_EXISTING: a file created while recording must also be preserved.
    Files.move(temporary, destination)
}

internal fun isMp4(path: Path): Boolean = Files.size(path) > 32 && Files.newInputStream(path).use {
    val header = it.readNBytes(12)
    header.size == 12 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
}
