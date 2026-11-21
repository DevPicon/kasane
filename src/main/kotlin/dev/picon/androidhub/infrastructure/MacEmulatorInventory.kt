package dev.picon.androidhub.infrastructure

import dev.picon.androidhub.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class MacEmulatorInventory(
    private val running: RunningEmulators,
    private val avdHome: Path = Path.of(System.getenv("ANDROID_AVD_HOME") ?: "${System.getProperty("user.home")}/.android/avd"),
    private val sdk: Path = Path.of(System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
        ?: "${System.getProperty("user.home")}/Library/Android/sdk"),
    private val logs: Path = Path.of(System.getProperty("user.home"), "Library", "Logs", "Android Hub"),
    userDevices: Path = Path.of(System.getenv("ANDROID_USER_HOME") ?: "${System.getProperty("user.home")}/.android", "devices.xml"),
) : EmulatorInventory, RunningEmulators by running {
    private data class Launch(val emulator: RunningEmulator, val process: Process, val grpcPort: Int)
    private val launches = mutableMapOf<String, Launch>()
    private val mutex = Mutex()
    private val formFactors = AvdFormFactorResolver(sdk, userDevices)

    override suspend fun list(): List<AvailableEmulator> {
        val active = running.discover()
        val configured = withContext(Dispatchers.IO) { configuredDevices() }
        val owned = mutex.withLock { launches.toMap() }
        val devices = configured.map { device ->
            val registered = active.firstOrNull { it.avdId == device.id }
            val instance = registered
                ?: owned[device.id]?.takeIf { it.process.isAlive }?.emulator
            val status = when {
                instance != null -> if (registered != null && bootCompleted(instance.serial)) DeviceStatus.READY else DeviceStatus.STARTING
                owned[device.id]?.process?.exitValue()?.let { it != 0 } == true -> DeviceStatus.ERROR
                else -> DeviceStatus.OFF
            }
            device.copy(status = status, running = instance,
                error = if (status == DeviceStatus.ERROR) "El emulador no pudo arrancar. Puedes reintentar. Los registros están en $logs." else null)
        }
        val extra = active.filter { instance -> devices.none { it.running?.instanceId == instance.instanceId } }
            .map { instance -> AvailableEmulator("running:${instance.serial}", instance.name, "",
                if (bootCompleted(instance.serial)) DeviceStatus.READY else DeviceStatus.STARTING, instance) }
        return (devices + extra).sortedBy { it.name.lowercase() }
    }

    override suspend fun start(id: String): RunningEmulator = mutex.withLock {
        val active = running.discover().firstOrNull { it.avdId == id }
        if (active != null) return@withLock active
        launches[id]?.takeIf { it.process.isAlive }?.let { return@withLock it.emulator }
        withContext(Dispatchers.IO) {
            val device = configuredDevices().firstOrNull { it.id == id }
                ?: throw SessionFailure("El AVD ya no está configurado. Actualiza el inventario.")
            val executable = sdk.resolve("emulator/emulator")
            if (!Files.isExecutable(executable) || !Files.isExecutable(sdk.resolve("platform-tools/adb"))) {
                throw SessionFailure("Faltan Android Emulator o Platform Tools en $sdk. Instálalos desde SDK Manager o configura ANDROID_HOME.")
            }
            val reserved = launches.values.filter { it.process.isAlive }.map { it.emulator.serial }.toSet() +
                running.discover().map { it.serial }
            val consolePort = (5554..5682 step 2).firstOrNull {
                "emulator-$it" !in reserved && portAvailable(it) && portAvailable(it + 1)
            } ?: throw SessionFailure("No hay puertos disponibles para iniciar otro emulador.")
            val grpcPort = generateSequence {
                ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
            }.first { candidate -> launches.values.none { it.process.isAlive && it.grpcPort == candidate } }
            Files.createDirectories(logs)
            val process = ProcessBuilder(executable.toString(), "-avd", id, "-port", consolePort.toString(),
                "-grpc", grpcPort.toString(), "-grpc-use-token", "-qt-hide-window", "-no-audio", "-no-boot-anim")
                .redirectErrorStream(true).redirectOutput(logs.resolve("$id.log").toFile()).start()
            val identity = "${process.pid()}@${process.info().startInstant().orElse(null)}"
            val instance = RunningEmulator("emulator-$consolePort", device.name, identity, id)
            launches[id] = Launch(instance, process, grpcPort)
            instance
        }
    }

    override suspend fun stop(emulator: RunningEmulator) {
        val owned = mutex.withLock { launches.values.firstOrNull { it.emulator.instanceId == emulator.instanceId } }
        running.stop(emulator)
        if (owned?.process?.isAlive == true) {
            // A process can be closed before it has registered its gRPC endpoint.
            owned.process.destroy()
            val stopped = withTimeoutOrNull(60_000) {
                while (owned.process.isAlive) delay(100)
                true
            } ?: false
            if (!stopped) throw SessionFailure("El proceso de arranque no terminó dentro del plazo de apagado.")
        }
        if (owned != null) mutex.withLock {
            launches.entries.removeIf { it.value === owned }
        }
    }

    override suspend fun delete(id: String): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            val device = configuredDevices().firstOrNull { it.id == id }
                ?: throw SessionFailure("El AVD ya no está configurado. Actualiza el inventario.")
            if (launches[id]?.process?.isAlive == true || running.discover().any { it.avdId == id }) {
                throw SessionFailure("Apaga el emulador antes de eliminarlo.")
            }
            val index = avdHome.resolve("${device.id}.ini")
            val directory = readIni(index)["path"]?.let { Path.of(it) } ?: avdHome.resolve("${device.id}.avd")
            if (!Files.isDirectory(directory) || Files.isSymbolicLink(index) || Files.isSymbolicLink(directory)) {
                throw SessionFailure("El registro del AVD no es válido para eliminarlo desde el Hub.")
            }
            val locked = Files.list(directory).use { paths -> paths.anyMatch {
                it.fileName.toString() == "hardware-qemu.ini.lock" ||
                    (it.fileName.toString().endsWith(".lock") && Files.isDirectory(it))
            } }
            if (locked) throw SessionFailure("El AVD tiene un bloqueo de uso. Cierra su instancia antes de eliminarlo.")
            SdkToolProcess(sdk.resolve("cmdline-tools/latest/bin/avdmanager"),
                mapOf("ANDROID_AVD_HOME" to avdHome.toString(), "ANDROID_SDK_ROOT" to sdk.toString()))
                .run(listOf("delete", "avd", "--name", id), timeoutMs = 30_000)
            if (Files.exists(index) || Files.exists(directory)) {
                throw SessionFailure("El CLI no eliminó todos los archivos del AVD. Revisa su estado antes de reintentar.")
            }
            launches.remove(id)
            Unit
        }
    }

    private fun configuredDevices(): List<AvailableEmulator> {
        if (!Files.isDirectory(avdHome)) return emptyList()
        return Files.list(avdHome).use { files ->
            files.filter { it.fileName.toString().endsWith(".ini") }.map { index ->
                val id = index.fileName.toString().removeSuffix(".ini")
                val registration = readIni(index)
                val directory = registration["path"]?.let { Path.of(it) } ?: avdHome.resolve("$id.avd")
                val config = readIni(directory.resolve("config.ini"))
                AvailableEmulator(id, config["avd.ini.displayname"] ?: id,
                    registration["target"]?.removePrefix("android-") ?: "", DeviceStatus.OFF,
                    formFactor = formFactors.resolve(config))
            }.toList()
        }
    }

    private suspend fun bootCompleted(serial: String): Boolean {
        val adb = sdk.resolve("platform-tools/adb")
        if (!Files.isExecutable(adb)) return false
        // ProcessBuilder.start must not be interrupted during macOS posix_spawn.
        // The probe itself is bounded, then structured cancellation can finish safely.
        return withContext(Dispatchers.IO) {
            val process = ProcessBuilder(adb.toString(), "-s", serial, "shell", "getprop", "sys.boot_completed")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0 &&
                    process.inputStream.bufferedReader().use { it.readText().trim() } == "1"
            } finally { process.destroyForcibly() }
        }
    }
}

private fun readIni(path: Path): Map<String, String> {
    if (!Files.isRegularFile(path)) return emptyMap()
    return Files.readAllLines(path).filter { it.contains('=') }.associate {
        val (key, value) = it.split('=', limit = 2)
        key.trim() to value.trim()
    }
}

private fun portAvailable(port: Int): Boolean = try {
    ServerSocket(port, 0, InetAddress.getLoopbackAddress()).use { true }
} catch (_: java.io.IOException) { false }
