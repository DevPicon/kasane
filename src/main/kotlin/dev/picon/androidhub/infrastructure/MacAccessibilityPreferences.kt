package dev.picon.androidhub.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Read once on launch. Missing preferences use the system's default of enabled motion. */
suspend fun readReduceMotion(): Boolean = runInterruptible(Dispatchers.IO) {
    try {
        val process = ProcessBuilder("/usr/bin/defaults", "read", "com.apple.universalaccess", "reduceMotion")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        try {
            process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0 &&
                process.inputStream.bufferedReader().use { it.readText().trim() } == "1"
        } finally {
            process.destroyForcibly()
        }
    } catch (_: IOException) {
        false
    }
}
