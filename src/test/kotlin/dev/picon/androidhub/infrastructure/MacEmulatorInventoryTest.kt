package dev.picon.androidhub.infrastructure

import dev.picon.androidhub.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MacEmulatorInventoryTest {
    @Test
    fun `delete checks identity running state and lock then removes only selected AVD`() = runTest {
        withInventory { inventory, source, root ->
            val cli = root.resolve("sdk/cmdline-tools/latest/bin/avdmanager")
            Files.createDirectories(cli.parent)
            Files.writeString(cli, "#!/bin/sh\n/bin/rm -rf \"\$ANDROID_AVD_HOME/\${4}.avd\" \"\$ANDROID_AVD_HOME/\${4}.ini\"\n")
            check(cli.toFile().setExecutable(true))
            assertFailsWith<SessionFailure> { inventory.delete("../Phone") }
            source.active += RunningEmulator("emulator-5554", "Phone", avdId = "Phone")
            assertFailsWith<SessionFailure> { inventory.delete("Phone") }
            source.active.clear()
            val lock = Files.createDirectory(root.resolve("avd/Phone.avd/hardware-qemu.ini.lock"))
            assertFailsWith<SessionFailure> { inventory.delete("Phone") }
            Files.delete(lock)
            Files.writeString(cli, "#!/bin/sh\necho 'Error: rejected'\nexit 1\n")
            assertFailsWith<SessionFailure> { inventory.delete("Phone") }
            assertEquals(2, inventory.list().size)
            Files.writeString(cli, "#!/bin/sh\n/bin/rm -rf \"\$ANDROID_AVD_HOME/\${4}.avd\" \"\$ANDROID_AVD_HOME/\${4}.ini\"\n")
            inventory.delete("Phone")
            assertEquals(listOf("Fold"), inventory.list().map { it.id })
            assertTrue(Files.exists(root.resolve("avd/Fold.avd/config.ini")))
        }
    }

    @Test
    fun `offline inventory resolves phone foldable and tablet from local metadata`() = runTest {
        withInventory { inventory, source, root ->
            val home = root.resolve("avd")
            val config = home.resolve("Phone.avd/config.ini")
            Files.writeString(config, "avd.ini.displayname=A Fold In Name Only\nhw.device.name=unrelated-id\nhw.device.manufacturer=Generic\n")
            Files.writeString(home.resolve("Fold.avd/config.ini"), "hw.sensor.hinge=yes\n")
            val tablet = Files.createDirectories(home.resolve("Tablet.avd"))
            Files.writeString(home.resolve("Tablet.ini"), "path=$tablet\ntarget=android-36.1\n")
            Files.writeString(tablet.resolve("config.ini"), "hw.device.name=another-id\nhw.device.manufacturer=Generic\n")
            val jar = root.resolve("sdk/cmdline-tools/latest/lib/sdklib.jar")
            Files.createDirectories(jar.parent)
            java.util.zip.ZipOutputStream(Files.newOutputStream(jar)).use {
                it.putNextEntry(java.util.zip.ZipEntry("com/android/sdklib/devices/devices.xml"))
                it.write(profiles("unrelated-id" to "normal", "another-id" to "large").toByteArray())
            }
            val devices = inventory.list().associateBy { it.id }
            assertEquals(FormFactor.HANDSET, devices.getValue("Phone").formFactor)
            assertEquals(FormFactor.FOLDABLE, devices.getValue("Fold").formFactor)
            assertEquals(FormFactor.TABLET, devices.getValue("Tablet").formFactor)
            assertTrue(devices.values.all { it.status == DeviceStatus.OFF })
            val phone = inventory.start("Phone")
            assertEquals(devices.values.associate { it.id to it.formFactor }, inventory.list().associate { it.id to it.formFactor })
            source.active += phone
            val ready = inventory.list().single { it.id == "Phone" }
            assertEquals(DeviceStatus.READY, ready.status)
            assertEquals(FormFactor.HANDSET, ready.formFactor)
            inventory.stop(phone)
        }
    }

    @Test
    fun `configured AVDs appear off without a running emulator or installed SDK`() = runTest {
        withInventory { inventory, _, root ->
            Files.delete(root.resolve("sdk/emulator/emulator"))
            val devices = inventory.list()
            assertEquals(setOf("Phone", "Fold"), devices.map { it.id }.toSet())
            assertTrue(devices.all { it.status == DeviceStatus.OFF && it.api == "36.1" })
            val error = assertFailsWith<SessionFailure> { inventory.start("Fold") }
            assertTrue(error.message!!.contains("SDK Manager"))
        }
    }

    @Test
    fun `starts are idempotent reserve distinct ports and can stop before endpoint registration`() = runTest {
        withInventory { inventory, source, _ ->
            val repeated = coroutineScope { listOf(async { inventory.start("Fold") }, async { inventory.start("Fold") }).awaitAll() }
            assertEquals(repeated[0].instanceId, repeated[1].instanceId)
            val phone = inventory.start("Phone")
            assertNotEquals(repeated[0].serial, phone.serial)
            assertTrue(inventory.list().all { it.status == DeviceStatus.STARTING })
            source.active += repeated[0]
            val devices = inventory.list()
            assertEquals(2, devices.size)
            assertEquals(DeviceStatus.READY, devices.single { it.id == "Fold" }.status)
            inventory.stop(repeated[0])
            inventory.stop(phone)
            assertTrue(inventory.list().all { it.status == DeviceStatus.OFF })
        }
    }
}

private suspend fun withInventory(block: suspend (MacEmulatorInventory, InventoryRunningFake, Path) -> Unit) = withContext(Dispatchers.IO) {
    val root = Files.createTempDirectory("hub-inventory-test")
    val home = Files.createDirectories(root.resolve("avd"))
    val sdk = Files.createDirectories(root.resolve("sdk"))
    for (name in listOf("Phone", "Fold")) {
        val directory = Files.createDirectories(home.resolve("$name.avd"))
        Files.writeString(home.resolve("$name.ini"), "path=$directory\ntarget=android-36.1\n")
        Files.writeString(directory.resolve("config.ini"), "avd.ini.displayname=Test $name\n")
    }
    val emulator = sdk.resolve("emulator/emulator")
    val adb = sdk.resolve("platform-tools/adb")
    Files.createDirectories(emulator.parent)
    Files.createDirectories(adb.parent)
    Files.writeString(emulator, "#!/bin/sh\nexec /bin/sleep 30\n")
    Files.writeString(adb, "#!/bin/sh\necho 1\n")
    check(emulator.toFile().setExecutable(true))
    check(adb.toFile().setExecutable(true))
    val source = InventoryRunningFake()
    val inventory = MacEmulatorInventory(source, home, sdk, root.resolve("logs"), root.resolve("devices.xml"))
    try {
        block(inventory, source, root)
    } finally {
        inventory.list().mapNotNull { it.running }.forEach { inventory.stop(it) }
        root.toFile().deleteRecursively()
    }
}

private class InventoryRunningFake : RunningEmulators {
    val active = mutableListOf<RunningEmulator>()
    override suspend fun discover() = active.toList()
    override suspend fun connect(serial: String): EmulatorSession = error("No screen in this fixture")
    override suspend fun stop(emulator: RunningEmulator) { active.remove(emulator) }
}
