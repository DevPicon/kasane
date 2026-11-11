package dev.picon.androidhub.presentation

/** Product identity is independent of the repository codename and macOS build counter. */
internal object ProductInfo {
    val displayName: String = System.getProperty("kasane.displayName", "Android Emulator Hub")
    val version: String = System.getProperty("kasane.version", "development")
    const val shortName: String = "Android Hub"
}
