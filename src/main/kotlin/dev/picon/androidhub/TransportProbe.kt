package dev.picon.androidhub

import dev.picon.androidhub.infrastructure.MacEmulatorDiscovery
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.picon.androidhub.core.TouchInput
import dev.picon.androidhub.core.DisplaySurface
import dev.picon.androidhub.core.EmulatorCommand
import dev.picon.androidhub.core.FoldPosture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Input is sent only with the explicit --exercise or --controls option. */
fun main(args: Array<String>): Unit = runBlocking {
    val discovery = MacEmulatorDiscovery()
    val serialIndex = args.indexOf("--serial")
    val requestedSerial = if (serialIndex >= 0) args.getOrNull(serialIndex + 1) else null
    val emulator = discovery.discover().firstOrNull { requestedSerial == null || it.serial == requestedSerial }
        ?: error("No se encontró la instancia solicitada con endpoint gRPC.")
    println("Emulador ${emulator.name}, ${emulator.serial}")
    discovery.connect(emulator.serial).use { session ->
        val latestSurface = MutableStateFlow<DisplaySurface?>(null)
        val controls = if ("--controls" in args) launch {
            val capabilities = session.capabilities()
            println("Capacidades=$capabilities")
            val initial = withTimeout(8000) { latestSurface.filterNotNull().first() }
            for (posture in listOf(FoldPosture.OPEN, FoldPosture.FOLDED, FoldPosture.HALF_OPEN, FoldPosture.OPEN).filter { it in capabilities.postures }) {
                val before = latestSurface.value ?: initial
                if (before.posture == posture) continue
                session.execute(EmulatorCommand.SetPosture(posture), before)
                val confirmed = withTimeout(8000) { latestSurface.filterNotNull().first { it.posture == posture } }
                println("Postura confirmada=$confirmed")
                delay(500)
            }
            if (capabilities.rotation) {
                val before = latestSurface.value ?: initial
                session.execute(EmulatorCommand.Rotate, before)
                println("Rotación confirmada=" + withTimeout(8000) { latestSurface.filterNotNull().first { it.rotation != before.rotation } })
            }
        } else null
        val exercise = if ("--exercise" in args) launch {
            delay(1000)
            val keyStarted = System.nanoTime()
            session.key("GoHome")
            println("RPC GoHome confirmado en %.2f ms".format((System.nanoTime() - keyStarted) / 1_000_000.0))
            delay(1500)
            try {
                for (step in 0..25) {
                    session.touch(TouchInput(0.5f, 0.85f - step * 0.02f, true))
                    delay(16)
                }
            } finally {
                session.releaseInput()
            }
            println("Arrastre enviado")
        } else null
        var count = 0
        var totalAgeMs = 0.0
        val started = System.nanoTime()
        withTimeoutOrNull(if (controls != null) 20_000 else 10_000) {
            session.frames.take(300).collect { frame ->
                count++
                latestSurface.value = frame.surface
                totalAgeMs += (System.currentTimeMillis() * 1000 - frame.generatedAtMicros) / 1000.0
                if (count == 1) println("Primer frame RGBA ${frame.size.width} × ${frame.size.height}, superficie=${frame.surface}")
            }
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
        println("Frames recibidos=$count, duración=%.2fs, media=%.2f fps".format(elapsed, count / elapsed))
        if (count > 0) println("Edad media del frame al recibir=%.2f ms".format(totalAgeMs / count))
        check(count > 0) { "No se recibió ningún frame." }
        exercise?.join()
        controls?.join()
    }
}
