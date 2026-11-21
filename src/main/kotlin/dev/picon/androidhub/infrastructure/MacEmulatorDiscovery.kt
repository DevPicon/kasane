package dev.picon.androidhub.infrastructure

import dev.picon.androidhub.core.EmulatorSession
import dev.picon.androidhub.core.RunningEmulator
import dev.picon.androidhub.core.RunningEmulators
import dev.picon.androidhub.core.SessionFailure
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.NoSuchFileException

/** Contains credentials, so deliberately has no generated toString(). */
internal class EmulatorEndpoint(
    val emulator: RunningEmulator,
    val port: Int,
    val token: String?,
    val processId: Long? = null,
)

class MacEmulatorDiscovery(
    private val directory: Path = Path.of(
        System.getProperty("user.home"), "Library", "Caches", "TemporaryItems", "avd", "running",
    ),
) : RunningEmulators {
    override suspend fun discover(): List<RunningEmulator> = endpoints().map { it.emulator }

    override suspend fun connect(serial: String): EmulatorSession {
        val endpoint = endpoints().firstOrNull { it.emulator.serial == serial }
            ?: error("La instancia ya no está disponible. Actualiza los emuladores.")
        return withContext(Dispatchers.IO) { GrpcEmulatorSession(endpoint) }
    }

    override suspend fun stop(emulator: RunningEmulator) {
        val endpoint = endpoints().firstOrNull {
            it.emulator.serial == emulator.serial && it.emulator.instanceId == emulator.instanceId
        } ?: return
        val process = endpoint.processId?.let { ProcessHandle.of(it).orElse(null) } ?: return
        withContext(Dispatchers.IO) {
            GrpcEmulatorSession(endpoint).use { session ->
                try {
                    session.shutdown()
                } catch (error: StatusException) {
                    if (!process.isAlive) return@withContext
                    // The server may close the connection before acknowledging its shutdown.
                    if (error.status.code !in setOf(Status.Code.UNAVAILABLE, Status.Code.CANCELLED)) {
                        throw SessionFailure(connectionMessage(error), error)
                    }
                }
            }
        }
        val stopped = withTimeoutOrNull(60_000) {
            while (process.isAlive) delay(100)
            true
        } ?: false
        if (!stopped) throw SessionFailure("La instancia no terminó dentro del plazo de apagado.")
    }

    internal suspend fun endpoints(): List<EmulatorEndpoint> = withContext(Dispatchers.IO) {
        if (!Files.isDirectory(directory)) return@withContext emptyList()
        Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().matches(Regex("pid_\\d+\\.ini")) }
                .map { path ->
                    val pid = path.fileName.toString().removePrefix("pid_").removeSuffix(".ini").toLong()
                    try {
                        if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) {
                            parseEndpoint(Files.readAllLines(path), pid)
                        } else null
                    } catch (_: NoSuchFileException) {
                        // Registrations can disappear while another instance finishes shutting down.
                        null
                    }
                }
                .filter { it != null }
                .toList()
                .filterNotNull()
                .sortedBy { it.emulator.serial }
        }
    }
}

internal fun parseEndpoint(lines: List<String>, pid: Long? = null): EmulatorEndpoint? {
    val values = lines.mapNotNull { line ->
        val split = line.indexOf('=')
        if (split <= 0) null else line.substring(0, split).trim() to line.substring(split + 1).trim()
    }.toMap()
    val serialPort = values["port.serial"]?.toIntOrNull() ?: return null
    val grpcPort = values["grpc.port"]?.toIntOrNull() ?: return null
    if (serialPort !in 1..65535 || grpcPort !in 1..65535) return null
    return EmulatorEndpoint(
        RunningEmulator(
            "emulator-$serialPort", values["avd.name"] ?: values["avd.id"] ?: "AVD",
            pid?.let { "$it@${ProcessHandle.of(it).flatMap { handle -> handle.info().startInstant() }.orElse(null)}" }
                ?: "emulator-$serialPort",
            values["avd.id"],
        ),
        grpcPort,
        values["grpc.token"]?.takeIf { it.isNotBlank() },
        pid,
    )
}
