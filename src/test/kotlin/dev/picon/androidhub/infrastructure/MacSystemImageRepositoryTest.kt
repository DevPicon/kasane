package dev.picon.androidhub.infrastructure

import dev.picon.androidhub.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MacSystemImageRepositoryTest {
    @Test fun `catalog filters host ABI previews and updates and de duplicates installed`() {
        val parsed = parseSystemImagePackages("""
            Installed packages:
              system-images;android-36.1;google_apis;arm64-v8a | 1 | Google APIs
            Available Packages:
              system-images;android-36.1;google_apis;arm64-v8a | 2 | Google APIs
              system-images;android-35;default;arm64-v8a | 1 | ARM
              system-images;android-35;default;x86_64 | 1 | Intel
              system-images;android-Baklava;default;arm64-v8a | 1 | Preview
              platforms;android-35 | 1 | Platform
            Available Updates:
              system-images;android-34;default;arm64-v8a | 1 | Update
        """.trimIndent(), "arm64-v8a")
        assertEquals(2, parsed.size)
        assertTrue(parsed.first().installed)
        assertEquals(35, parsed.last().image.api)
        assertEquals(ImageDownloadProgress(45, "45% Unzipping"), parseImageDownloadProgress("[===] 20% Downloading\r[====] 45% Unzipping"))
    }

    @Test fun `install verifies files and never reinstalls a present package`() = runTest {
        fixture { sdk, cli, repository ->
            Files.writeString(cli, script("mkdir -p '$sdk/system-images/android-35/google_apis/arm64-v8a'\ntouch '$sdk/system-images/android-35/google_apis/arm64-v8a/system.img' '$sdk/system-images/android-35/google_apis/arm64-v8a/source.properties'\necho '[===] 100% Complete'"))
            val progress = mutableListOf<ImageDownloadProgress>()
            repository.install(imageId, progress::add)
            assertEquals(100, progress.last().percent)
            assertTrue(repository.list().single().installed)
            Files.writeString(cli, script("exit 7"))
            repository.install(imageId) { error("Already installed") }
            assertFailsWith<SessionFailure> { repository.install("../anything") {} }
        }
    }

    @Test fun `license refusal false success and insufficient disk are recoverable errors`() = runTest {
        fixture { sdk, cli, repository ->
            Files.writeString(cli, script("read answer\n[ \"\$answer\" = n ] || exit 8\necho 'Licenses not accepted. Accept? (y/N)'\nexit 1"))
            val license = assertFailsWith<SessionFailure> { repository.install(imageId) {} }
            assertTrue(license.message!!.contains("sdkmanager --licenses"))
            Files.writeString(cli, script("echo '[===] 100% Complete'"))
            assertFailsWith<SessionFailure> { repository.install(imageId) {} }
            assertFailsWith<SessionFailure> { MacSystemImageRepository(sdk, "arm64-v8a") { 0 }.install(imageId) {} }
        }
    }

    @Test fun `cancel stops the installer and leaves shared installed images untouched`() = runTest {
        fixture { sdk, cli, repository ->
            val pid = sdk.resolve("installer.pid")
            Files.writeString(cli, script("echo \$\$ > '$pid'\nexec /bin/sleep 30"))
            coroutineScope {
                val job = launch(Dispatchers.IO) { repository.install(imageId) {} }
                withTimeout(5000) { while (!Files.exists(pid)) delay(20) }
                val processId = Files.readString(pid).trim().toLong()
                job.cancelAndJoin()
                assertFalse(ProcessHandle.of(processId).map { it.isAlive }.orElse(false))
            }
            assertTrue(Files.exists(sdk.resolve("shared-image")))
        }
    }
}

private const val imageId = "system-images;android-35;google_apis;arm64-v8a"
private fun script(install: String): String = """#!/bin/sh
if [ "${'$'}1" = --list ]; then
  echo 'Available Packages:'
  echo ' $imageId | 1 | Google APIs'
  exit 0
fi
$install
"""

private suspend fun fixture(block: suspend (Path, Path, MacSystemImageRepository) -> Unit) = withContext(Dispatchers.IO) {
    val sdk = Files.createTempDirectory("hub-images-test")
    val cli = sdk.resolve("cmdline-tools/latest/bin/sdkmanager")
    Files.createDirectories(cli.parent)
    Files.writeString(cli, script("exit 1"))
    check(cli.toFile().setExecutable(true))
    Files.writeString(sdk.resolve("shared-image"), "keep")
    try { block(sdk, cli, MacSystemImageRepository(sdk, "arm64-v8a") { 50L * 1024 * 1024 * 1024 }) }
    finally { sdk.toFile().deleteRecursively() }
}
