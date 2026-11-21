package dev.picon.androidhub.infrastructure

import dev.picon.androidhub.core.FormFactor
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class AvdFormFactorResolverTest {
    @Test
    fun `profile hinge metadata identifies foldables before creating the AVD`() {
        val xml = profiles("generic" to "normal").replace("</d:hardware>", "<d:hinge><d:count>1</d:count></d:hinge></d:hardware>")
        assertEquals(FormFactor.FOLDABLE, readProfiles(xml.byteInputStream()).single().second)
        val emptyRegion = profiles("generic" to "normal").replace("</d:screen>", "<d:foldable-region><d:x-folded-dimension>0</d:x-folded-dimension><d:y-folded-dimension>0</d:y-folded-dimension></d:foldable-region></d:screen>")
        assertEquals(FormFactor.HANDSET, readProfiles(emptyRegion.byteInputStream()).single().second)
    }

    @Test
    fun `classification uses profile metadata and fold hardware instead of names or resolution`() = fixture { sdk, custom ->
        val resolver = AvdFormFactorResolver(sdk, custom)
        val phone = mapOf("hw.device.name" to "profile-a", "hw.device.manufacturer" to "Generic",
            "avd.ini.displayname" to "Pixel Fold Tablet", "hw.lcd.width" to "2560", "hw.lcd.height" to "1600")
        assertEquals(FormFactor.HANDSET, resolver.resolve(phone))
        assertEquals(FormFactor.HANDSET, resolver.resolve(phone + ("avd.ini.displayname" to "Renamed")))
        assertEquals(FormFactor.TABLET, resolver.resolve(phone + ("hw.device.name" to "profile-b")))
        assertEquals(FormFactor.FOLDABLE, resolver.resolve(mapOf("hw.sensor.hinge" to "true")))
        assertEquals(FormFactor.FOLDABLE, resolver.resolve(phone + ("hw.sensor.hinge" to "yes")))
        assertEquals(FormFactor.FOLDABLE, resolver.resolve(mapOf("hw.sensor.roll" to "1")))
    }

    @Test
    fun `missing and contradictory evidence preserves unknown category`() = fixture { sdk, custom ->
        val resolver = AvdFormFactorResolver(sdk, custom)
        assertEquals(FormFactor.UNKNOWN, resolver.resolve(mapOf("avd.ini.displayname" to "Fold", "hw.lcd.width" to "1080", "hw.lcd.height" to "2400")))
        assertEquals(FormFactor.UNKNOWN, resolver.resolve(mapOf("hw.device.name" to "profile-a")))
        assertEquals(FormFactor.UNKNOWN, resolver.resolve(mapOf("hw.sensor.hinge" to "yes", "tag.id" to "android-wear")))
        assertEquals(FormFactor.UNKNOWN, resolver.resolve(mapOf("tag.ids" to "android-tv,android-wear")))
        assertEquals(FormFactor.UNKNOWN, resolver.resolve(mapOf("hw.device.name" to "profile-b", "hw.device.manufacturer" to "Generic", "tag.id" to "android-tv")))
    }

    @Test
    fun `specialized catalogs and image tags expose their own categories`() = fixture { sdk, custom ->
        val resolver = AvdFormFactorResolver(sdk, custom)
        for ((id, category) in listOf("watch" to FormFactor.WEARABLE, "tv" to FormFactor.TV, "car" to FormFactor.AUTOMOTIVE)) {
            assertEquals(category, resolver.resolve(mapOf("hw.device.name" to id, "hw.device.manufacturer" to "Generic")))
        }
        assertEquals(FormFactor.TV, resolver.resolve(mapOf("tag.id" to "android-tv")))
        assertEquals(FormFactor.AUTOMOTIVE, resolver.resolve(mapOf("tag.id" to "android-automotive-playstore")))
    }

    @Test
    fun `conflicting custom profile falls back and malformed catalog is ignored`() = fixture { sdk, custom ->
        Files.writeString(custom, profiles("profile-a" to "large"))
        assertEquals(FormFactor.UNKNOWN, AvdFormFactorResolver(sdk, custom).resolve(
            mapOf("hw.device.name" to "profile-a", "hw.device.manufacturer" to "Generic")))
        assertTrue(readProfiles("<broken>".byteInputStream()).isEmpty())
        assertTrue(readProfiles("<!DOCTYPE devices SYSTEM 'file:///not-a-profile'><devices/>".byteInputStream()).isEmpty())
    }

    private fun fixture(block: (java.nio.file.Path, java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("hub-form-factors")
        try {
            val jar = root.resolve("cmdline-tools/latest/lib/sdklib/sdklib.core.jar")
            Files.createDirectories(jar.parent)
            ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
                for ((name, xml) in mapOf(
                    "devices.xml" to profiles("profile-a" to "normal", "profile-b" to "large"),
                    "wear.xml" to profiles("watch" to "small"),
                    "tv.xml" to profiles("tv" to "xlarge"),
                    "automotive.xml" to profiles("car" to "normal"),
                )) {
                    zip.putNextEntry(ZipEntry("com/android/sdklib/devices/$name")); zip.write(xml.toByteArray()); zip.closeEntry()
                }
            }
            block(root, root.resolve("devices.xml"))
        } finally { root.toFile().deleteRecursively() }
    }
}

internal fun profiles(vararg devices: Pair<String, String>): String = """
    <d:devices xmlns:d="http://schemas.android.com/sdk/devices/9">
    ${devices.joinToString("") { (id, size) -> "<d:device><d:id>$id</d:id><d:manufacturer>Generic</d:manufacturer><d:hardware><d:screen><d:screen-size>$size</d:screen-size></d:screen></d:hardware></d:device>" }}
    </d:devices>
""".trimIndent()
