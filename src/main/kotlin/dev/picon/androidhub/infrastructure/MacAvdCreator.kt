package dev.picon.androidhub.infrastructure

import dev.picon.androidhub.core.*
import kotlinx.coroutines.*
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit

class MacAvdCreator(
    private val sdk: Path = Path.of(System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME") ?: "${System.getProperty("user.home")}/Library/Android/sdk"),
    private val avdHome: Path = Path.of(System.getenv("ANDROID_AVD_HOME") ?: "${System.getProperty("user.home")}/.android/avd"),
    private val userHome: Path = Path.of(System.getenv("ANDROID_USER_HOME") ?: "${System.getProperty("user.home")}/.android"),
    private val hostAbi: String = if (System.getProperty("os.arch") in setOf("arm64", "aarch64")) "arm64-v8a" else "x86_64",
    private val usableSpace: (Path) -> Long = { Files.getFileStore(it).usableSpace },
) : AvdCreator {
    private val cli get() = sdk.resolve("cmdline-tools/latest/bin/avdmanager")

    override suspend fun catalog(): AvdCreationCatalog = withContext(Dispatchers.IO) {
        if (!Files.isExecutable(cli)) throw SessionFailure("Falta avdmanager. Instala Android SDK Command-line Tools (latest) o configura ANDROID_HOME.")
        val listed = command(listOf("list", "device"), userHome)
        val metadata = AvdFormFactorResolver(sdk, userHome.resolve("devices.xml")).hardwareProfiles()
        val profiles = parseCliProfiles(listed).mapNotNull { device ->
            metadata.firstOrNull { it.id == device.id && it.manufacturer.equals(device.manufacturer, ignoreCase = true) }
                ?.copy(name = device.name, requiredTag = device.requiredTag)
        }.groupBy { it.id }.values.filter { it.size == 1 }.map { it.single() }
        val images = installedImages()
        val names = if (Files.isDirectory(avdHome)) Files.list(avdHome).use { files ->
            files.filter { it.fileName.toString().endsWith(".ini") || it.fileName.toString().endsWith(".avd") }
                .map { it.fileName.toString().substringBeforeLast('.') }.toList().toSet()
        } else emptySet()
        AvdCreationCatalog(profiles.filter { profile -> images.any(profile::supports) }.sortedBy { it.name.lowercase() }, images, names)
    }

    override suspend fun create(request: AvdCreationRequest): String = withContext(Dispatchers.IO) {
        val fresh = catalog()
        request.validationError(fresh)?.let { throw SessionFailure(it) }
        Files.createDirectories(avdHome)
        FileChannel.open(avdHome.resolve(".android-hub-create.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                ?: throw SessionFailure("Ya hay otra creación de AVD en curso. Reintenta cuando termine.")
            lock.use {
                val destination = avdHome.resolve("${request.name}.avd")
                val index = avdHome.resolve("${request.name}.ini")
                if (Files.exists(destination) || Files.exists(index)) throw SessionFailure("Ya existe un AVD con ese nombre.")
                val needed = (request.storageGb.toLong() + 2) * 1024 * 1024 * 1024
                if (usableSpace(avdHome) < needed) throw SessionFailure("No hay espacio suficiente. Se necesitan al menos ${request.storageGb + 2} GB libres.")
                val staging = Files.createTempDirectory(avdHome, ".android-hub-create-")
                val ownership = UUID.randomUUID().toString()
                var published = false
                var moved = false
                try {
                    command(listOf("create", "avd", "--name", request.name, "--package", request.imageId, "--device", request.profileId), staging)
                    currentCoroutineContext().ensureActive()
                    val created = staging.resolve("${request.name}.avd")
                    val config = created.resolve("config.ini")
                    if (!Files.isRegularFile(config)) throw SessionFailure("avdmanager no generó una configuración válida.")
                    val values = ini(config).toMutableMap().apply {
                        this["hw.ramSize"] = request.ramMb.toString()
                        this["disk.dataPartition.size"] = "${request.storageGb}G"
                        this["avd.ini.displayname"] = request.name
                        this["AvdId"] = request.name
                        this["hw.keyboard"] = "yes"
                        remove("disk.dataPartition.path")
                    }
                    writeIni(config, values)
                    val stagedIndex = staging.resolve("${request.name}.ini")
                    if (!Files.isRegularFile(stagedIndex)) throw SessionFailure("avdmanager no registró el AVD creado.")
                    writeIni(stagedIndex, ini(stagedIndex).toMutableMap().apply {
                        this["path"] = destination.toString()
                        this["target"] = request.imageId.split(';')[1]
                        remove("path.rel")
                    })
                    Files.writeString(created.resolve(".android-hub-creation"), ownership)
                    currentCoroutineContext().ensureActive()
                    // No replace-existing flags: publication cannot overwrite an existing AVD.
                    Files.move(created, destination)
                    moved = true
                    Files.createLink(index, stagedIndex)
                    published = true
                    Files.deleteIfExists(destination.resolve(".android-hub-creation"))
                    request.name
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        if (moved && !published && Files.isRegularFile(destination.resolve(".android-hub-creation")) &&
                            Files.readString(destination.resolve(".android-hub-creation")) == ownership) removeOwnedTree(destination)
                        removeOwnedTree(staging)
                    }
                }
            }
        }
    }

    private fun installedImages(): List<InstalledSystemImage> {
        val root = sdk.resolve("system-images")
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root, 4).use { files ->
            files.filter { it.fileName.toString() == "source.properties" && Files.isRegularFile(it.parent.resolve("system.img")) }.map { source ->
                val values = ini(source)
                val abi = values["SystemImage.Abi"]
                val api = values["AndroidVersion.ApiLevel"]?.substringBefore('.')?.toIntOrNull()
                val tag = values["SystemImage.TagId"]
                val segments = root.relativize(source.parent).map { it.toString() }
                if (abi != hostAbi || api == null || tag == null || segments.size != 3) null else InstalledSystemImage(
                    "system-images;" + segments.joinToString(";"),
                    "API ${values["AndroidVersion.ApiLevel"]} · ${values["SystemImage.TagDisplay"] ?: tag} · $abi", api, tag, abi,
                )
            }.toList().filterNotNull().sortedByDescending { it.api }
        }
    }

    private suspend fun command(arguments: List<String>, home: Path): String = withContext(Dispatchers.IO) {
        val log = Files.createTempFile("android-hub-avdmanager-", ".log")
        var process: Process? = null
        try {
            process = ProcessBuilder(listOf(cli.toString()) + arguments).apply {
                environment()["ANDROID_AVD_HOME"] = home.toString()
                environment()["ANDROID_USER_HOME"] = userHome.toString()
                environment()["ANDROID_SDK_ROOT"] = sdk.toString()
                redirectErrorStream(true)
                redirectOutput(log.toFile())
            }.start()
            process.outputStream.bufferedWriter().use { if (arguments.firstOrNull() == "create") it.write("no\n") }
            val exited = withTimeoutOrNull(120_000) { while (process.isAlive) delay(100); true } ?: false
            if (!exited) throw SessionFailure("avdmanager superó el plazo de dos minutos. Puedes reintentar.")
            val output = Files.readString(log)
            if (process.exitValue() != 0) throw SessionFailure("No se pudo completar la operación. " + output.lineSequence().filter { it.startsWith("Error:") }.lastOrNull().orEmpty().take(400))
            output
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                process?.let { running ->
                    if (running.isAlive) {
                        val children = running.descendants().toList()
                        children.forEach { it.destroy() }
                        running.destroy()
                        if (!running.waitFor(2, TimeUnit.SECONDS)) running.destroyForcibly().waitFor(2, TimeUnit.SECONDS)
                        children.filter { it.isAlive }.forEach { it.destroyForcibly() }
                    }
                }
                Files.deleteIfExists(log)
            }
        }
    }
}

private fun ini(path: Path): Map<String, String> {
    val values = Properties().apply { Files.newBufferedReader(path).use { load(it) } }
    return values.stringPropertyNames().associateWith { values.getProperty(it) }
}

private fun writeIni(path: Path, values: Map<String, String>) = Files.writeString(path, values.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })

private fun removeOwnedTree(path: Path) {
    if (Files.exists(path)) Files.walk(path).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
}

internal fun parseCliProfiles(output: String): List<AvdHardwareProfile> = output.split(Regex("(?m)^id: ")).drop(1).mapNotNull { block ->
    val id = Regex("^\\d+ or \"([^\"]+)\"").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
    fun value(key: String) = Regex("(?m)^\\s*$key\\s*:\\s*(.+)$").find(block)?.groupValues?.get(1)?.trim()
    AvdHardwareProfile(id, value("Name") ?: id, value("OEM") ?: "", FormFactor.UNKNOWN, requiredTag = value("Tag"))
}
