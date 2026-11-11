package dev.picon.androidhub.infrastructure

import dev.picon.androidhub.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class MacAvdCreatorTest {
    @Test
    fun `missing space prevents writing an AVD`() = runTest {
        fixture { _, root ->
            val creator = MacAvdCreator(root.resolve("sdk"), root.resolve("avd"), root.resolve("user"), "arm64-v8a", { 0 })
            val failure = assertFailsWith<SessionFailure> { creator.create(request(creator.catalog())) }
            assertTrue(failure.message!!.contains("espacio"))
            assertFalse(Files.exists(root.resolve("avd/My_Tablet.ini")))
        }
    }

    @Test
    fun `catalog only exposes installed host images and CLI supported profiles`() = runTest {
        fixture { creator, root ->
            val catalog = creator.catalog()
            assertEquals(listOf("phone"), catalog.profiles.map { it.id })
            assertEquals(listOf("arm64-v8a"), catalog.images.map { it.abi })
            assertEquals(36, catalog.images.single().api)
            assertTrue(catalog.existingNames.isEmpty())
            Files.delete(root.resolve("sdk/system-images/android-36.1/google_apis_playstore/arm64-v8a/system.img"))
            assertTrue(creator.catalog().images.isEmpty())
            assertTrue(creator.catalog().profiles.isEmpty())
        }
    }

    @Test
    fun `creation publishes final paths and requested resources without overwriting an AVD`() = runTest {
        fixture { creator, root ->
            val request = request(creator.catalog())
            assertEquals("My_Tablet", creator.create(request))
            val home = root.resolve("avd")
            val config = Files.readString(home.resolve("My_Tablet.avd/config.ini"))
            assertTrue(config.contains("hw.ramSize=2048"))
            assertTrue(config.contains("disk.dataPartition.size=4G"))
            val index = Files.readString(home.resolve("My_Tablet.ini"))
            assertTrue(index.contains("path=${home.resolve("My_Tablet.avd")}"))
            assertTrue(index.contains("target=android-36.1"))
            assertFalse(index.contains(".android-hub-create-"))
            assertFailsWith<SessionFailure> { creator.create(request) }
            assertEquals(config, Files.readString(home.resolve("My_Tablet.avd/config.ini")))
            assertEquals(setOf("My_Tablet"), creator.catalog().existingNames)
        }
    }

    @Test
    fun `CLI failure and cancellation remove only their staged files`() = runTest {
        fixture { creator, root ->
            val cli = root.resolve("sdk/cmdline-tools/latest/bin/avdmanager")
            val original = Files.readString(cli)
            Files.writeString(cli, original.replace("# CREATE", "echo 'Error: test failure'; exit 1"))
            assertFailsWith<SessionFailure> { creator.create(request(creator.catalog())) }
            assertFalse(Files.exists(root.resolve("avd/My_Tablet.ini")))
            Files.writeString(cli, original.replace("# AFTER_CREATE", "sleep 30"))
            val catalog = creator.catalog()
            val work = launch(Dispatchers.Default) { creator.create(request(catalog)) }
            val pid = withContext(Dispatchers.IO) { withTimeout(5000) {
                var marker: Path? = null
                while (marker == null) {
                    if (Files.isDirectory(root.resolve("avd"))) marker = Files.walk(root.resolve("avd")).use { paths -> paths.filter { it.fileName.toString() == "pid" }.findFirst().orElse(null) }
                    if (marker == null) delay(20)
                }
                Files.readString(marker).trim().toLong()
            } }
            work.cancelAndJoin()
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            assertFalse(Files.exists(root.resolve("avd/My_Tablet.ini")))
            assertTrue(Files.list(root.resolve("avd")).use { paths -> paths.noneMatch { it.fileName.toString().startsWith(".android-hub-create-") } })
        }
    }

    private fun request(catalog: AvdCreationCatalog) = AvdCreationRequest("My_Tablet", "phone", catalog.images.single().id, 2048, 4)

    private suspend fun fixture(block: suspend (MacAvdCreator, Path) -> Unit) = withContext(Dispatchers.IO) {
        val root = Files.createTempDirectory("hub-creator-test")
        try {
            val sdk = root.resolve("sdk")
            val cli = sdk.resolve("cmdline-tools/latest/bin/avdmanager")
            Files.createDirectories(cli.parent)
            Files.writeString(cli, """
                #!/bin/sh
                if [ "${'$'}1" = list ]; then
                  echo 'id: 0 or "phone"'
                  echo '    Name: A phone'
                  echo '    OEM : Generic'
                  exit 0
                fi
                read answer
                # CREATE
                while [ "${'$'}#" -gt 0 ]; do
                  if [ "${'$'}1" = --name ]; then shift; name="${'$'}1"; fi
                  shift
                done
                mkdir -p "${'$'}ANDROID_AVD_HOME/${'$'}name.avd"
                echo "path=${'$'}ANDROID_AVD_HOME/${'$'}name.avd" > "${'$'}ANDROID_AVD_HOME/${'$'}name.ini"
                echo 'target=android-0' >> "${'$'}ANDROID_AVD_HOME/${'$'}name.ini"
                echo 'hw.device.name=phone' > "${'$'}ANDROID_AVD_HOME/${'$'}name.avd/config.ini"
                echo ${'$'}${'$'} > "${'$'}ANDROID_AVD_HOME/${'$'}name.avd/pid"
                # AFTER_CREATE
            """.trimIndent())
            check(cli.toFile().setExecutable(true))
            val jar = sdk.resolve("cmdline-tools/latest/lib/sdklib.jar")
            Files.createDirectories(jar.parent)
            ZipOutputStream(Files.newOutputStream(jar)).use {
                it.putNextEntry(ZipEntry("com/android/sdklib/devices/devices.xml")); it.write(profiles("phone" to "normal").toByteArray())
            }
            for (abi in listOf("arm64-v8a", "x86_64")) {
                val image = Files.createDirectories(sdk.resolve("system-images/android-36.1/google_apis_playstore/$abi"))
                Files.writeString(image.resolve("system.img"), "fixture")
                Files.writeString(image.resolve("source.properties"), "AndroidVersion.ApiLevel=36.1\nSystemImage.TagId=google_apis_playstore\nSystemImage.Abi=$abi\n")
            }
            block(MacAvdCreator(sdk, root.resolve("avd"), root.resolve("user"), "arm64-v8a", { 100L * 1024 * 1024 * 1024 }), root)
        } finally { root.toFile().deleteRecursively() }
    }
}
