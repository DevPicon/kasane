package dev.picon.androidhub.infrastructure

import dev.picon.androidhub.core.FormFactor
import dev.picon.androidhub.core.AvdHardwareProfile
import org.w3c.dom.Element
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Reads the installed SDK's profile catalog once; no Android Studio or running guest required. */
internal class AvdFormFactorResolver(
    private val sdk: Path,
    private val userDevices: Path = Path.of(
        System.getenv("ANDROID_USER_HOME") ?: "${System.getProperty("user.home")}/.android", "devices.xml",
    ),
) {
    private val catalog by lazy { loadProfiles() }
    private val profiles by lazy { catalog.groupBy({ ProfileKey(it.id, it.manufacturer.lowercase(Locale.ROOT)) }, { it.formFactor }).mapValues { it.value.toSet() } }

    fun hardwareProfiles(): List<AvdHardwareProfile> = catalog.distinct()

    fun resolve(config: Map<String, String>): FormFactor {
        val key = ProfileKey(config["hw.device.name"].orEmpty(), config["hw.device.manufacturer"].orEmpty().lowercase(Locale.ROOT))
        val matching = profiles[key].orEmpty()
        if (matching.size > 1) return FormFactor.UNKNOWN
        val tags = (config["tag.id"].orEmpty() + "," + config["tag.ids"].orEmpty()).split(',', ';')
        val specialized = tags.mapNotNull {
            when (it.trim()) {
                "android-wear", "android-wear-cn" -> FormFactor.WEARABLE
                "android-tv", "google-tv" -> FormFactor.TV
                "android-automotive", "android-automotive-playstore" -> FormFactor.AUTOMOTIVE
                else -> null
            }
        }.toSet() + matching.filter { it in setOf(FormFactor.WEARABLE, FormFactor.TV, FormFactor.AUTOMOTIVE) }
        val foldable = listOf("hw.sensor.hinge", "hw.sensor.roll").any { config[it]?.lowercase(Locale.ROOT) in setOf("yes", "true", "1") }
        if (specialized.size > 1 || (foldable && specialized.isNotEmpty())) return FormFactor.UNKNOWN
        if (specialized.isNotEmpty()) {
            if (matching.any { it != specialized.single() }) return FormFactor.UNKNOWN
            return specialized.single()
        }
        if (foldable) return FormFactor.FOLDABLE
        return matching.singleOrNull() ?: FormFactor.UNKNOWN
    }

    private fun loadProfiles(): List<AvdHardwareProfile> {
        val entries = mutableListOf<AvdHardwareProfile>()
        val cli = sdk.resolve("cmdline-tools")
        val jars = try {
            if (Files.isDirectory(cli)) Files.walk(cli, 5).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().let { name -> name.endsWith(".jar") && name.contains("sdklib") } }.toList()
            } else emptyList()
        } catch (_: IOException) { emptyList() }
        for (path in jars + sdk.resolve("tools/lib/sdklib.jar")) {
            if (!Files.isRegularFile(path)) continue
            try {
                ZipFile(path.toFile()).use { jar ->
                    for (entry in jar.entries()) {
                        if (!entry.name.startsWith("com/android/sdklib/devices/") || !entry.name.endsWith(".xml")) continue
                        jar.getInputStream(entry).use { entries += readHardwareProfiles(it, entry.name.substringAfterLast('/')) }
                    }
                }
            } catch (_: IOException) {
                // An unavailable catalog must not hide configured AVDs.
            }
        }
        if (Files.isRegularFile(userDevices)) try {
            Files.newInputStream(userDevices).use { entries += readHardwareProfiles(it) }
        } catch (_: IOException) {
            // Preserve the generic fallback when custom profiles cannot be read.
        }
        return entries
    }
}

internal data class ProfileKey(val id: String, val manufacturer: String)

internal fun readProfiles(input: InputStream, catalog: String = "devices.xml"): List<Pair<ProfileKey, FormFactor>> =
    readHardwareProfiles(input, catalog).map { ProfileKey(it.id, it.manufacturer.lowercase(Locale.ROOT)) to it.formFactor }

private fun readHardwareProfiles(input: InputStream, catalog: String = "devices.xml"): List<AvdHardwareProfile> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    val document = try {
        factory.newDocumentBuilder().apply { setErrorHandler(DefaultHandler()) }.parse(input)
    } catch (_: SAXException) {
        return emptyList()
    }
    val devices = document.getElementsByTagNameNS("*", "device")
    return (0 until devices.length).mapNotNull { index ->
        val device = devices.item(index) as Element
        val id = device.child("id")?.textContent?.trim() ?: device.child("name")?.textContent?.trim() ?: return@mapNotNull null
        val manufacturer = device.child("manufacturer")?.textContent?.trim() ?: return@mapNotNull null
        val hardware = device.child("hardware")
        val props = device.getElementsByTagNameNS("*", "boot-prop")
        val boot = (0 until props.length).map { props.item(it) as Element }.associate {
            it.child("prop-name")?.textContent?.trim() to it.child("prop-value")?.textContent?.trim()
        }
        val region = hardware?.child("screen")?.child("foldable-region")
        val foldedPanel = listOf("x-folded-dimension", "y-folded-dimension").all { (region?.child(it)?.textContent?.trim()?.toIntOrNull() ?: 0) > 0 }
        val foldingHardware = listOf("hinge", "roll").any { (hardware?.child(it)?.child("count")?.textContent?.trim()?.toIntOrNull() ?: 0) > 0 } || foldedPanel
        val category = when (catalog) {
            "wear.xml" -> FormFactor.WEARABLE
            "tv.xml" -> FormFactor.TV
            "automotive.xml" -> FormFactor.AUTOMOTIVE
            "desktop.xml", "xr.xml" -> FormFactor.UNKNOWN
            else -> if (foldingHardware || listOf("hw.sensor.hinge", "hw.sensor.roll").any { boot[it] in setOf("yes", "true", "1") }) FormFactor.FOLDABLE else when (hardware?.child("screen")?.child("screen-size")?.textContent?.trim()) {
                "small", "normal" -> FormFactor.HANDSET
                "large", "xlarge" -> FormFactor.TABLET
                else -> FormFactor.UNKNOWN
            }
        }
        fun quantity(element: Element?, target: String): Int? {
            val value = element?.textContent?.trim()?.toDoubleOrNull() ?: return null
            val bytes = value * when (element.getAttribute("unit")) {
                "KiB" -> 1024.0
                "MiB" -> 1024.0 * 1024
                "GiB" -> 1024.0 * 1024 * 1024
                else -> return null
            }
            return (bytes / if (target == "MiB") 1048576 else 1073741824).toInt()
        }
        val api = device.child("software")?.child("api-level")?.textContent?.trim()?.substringBefore('-')?.toIntOrNull() ?: 1
        AvdHardwareProfile(id, device.child("name")?.textContent?.trim() ?: id, manufacturer, category,
            ramMb = (quantity(hardware?.child("ram"), "MiB") ?: 2048).coerceIn(512, 4096),
            storageGb = (quantity(hardware?.child("internal-storage"), "GiB") ?: 4).coerceIn(2, 8), minimumApi = api)
    }
}

private fun Element.child(name: String): Element? = (0 until childNodes.length)
    .map { childNodes.item(it) }.filterIsInstance<Element>().firstOrNull { it.localName == name }
