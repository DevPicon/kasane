package dev.picon.androidhub.infrastructure

import dev.picon.androidhub.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class MacEmulatorCaptureTest {
    @Test fun `only the unique owned screenrecord process may be signalled`() {
        val remote = "/data/local/tmp/kasane-unique.mp4"
        assertTrue(ownsRecording("screenrecord\u0000--time-limit\u0000180\u0000$remote\u0000", remote))
        assertFalse(ownsRecording("screenrecord\u0000/other.mp4\u0000", remote))
        assertFalse(ownsRecording("sh\u0000-c\u0000screenrecord $remote\u0000", remote))
        assertFalse(ownsRecording("other\u0000$remote\u0000", remote))
    }

    @Test fun `screenshot exports PNG and preserves existing destination and refuses stale instance`() = runTest {
        captureFixture { root, source, target ->
            val png = root.resolve("capture with spaces.png")
            source.screenshot(target, png.toString())
            assertEquals(1, ImageIO.read(png.toFile()).width)
            val original = Files.readAllBytes(png)
            assertFailsWith<SessionFailure> { source.screenshot(target, png.toString()) }
            assertContentEquals(original, Files.readAllBytes(png))
            assertFailsWith<SessionFailure> { source.screenshot(target.copy(instanceId = "stale"), root.resolve("stale.png").toString()) }
            assertFalse(Files.exists(root.resolve("stale.png")))
            assertTrue(Files.list(root).use { it.noneMatch { p -> p.fileName.toString().endsWith(".partial") } })
        }
    }

    @Test fun `stop finalizes MP4 and restores taps including absent setting`() = runTest {
        captureFixture { root, source, target ->
            for (original in listOf("0", "1", "null")) {
                Files.writeString(root.resolve("taps"), original)
                val stop = CompletableDeferred<Unit>()
                val video = root.resolve("capture-$original.mp4")
                source.record(target, video.toString(), true, stop) {
                    assertEquals("1", Files.readString(root.resolve("taps")))
                    stop.complete(Unit)
                }
                assertTrue(isMp4(video))
                assertEquals(original, Files.readString(root.resolve("taps")))
            }
        }
    }

    @Test fun `taps can be hidden temporarily and external changes are preserved`() = runTest {
        captureFixture { root, source, target ->
            Files.writeString(root.resolve("taps"), "1")
            val stop = CompletableDeferred<Unit>()
            source.record(target, root.resolve("no-taps.mp4").toString(), false, stop) {
                assertEquals("0", Files.readString(root.resolve("taps")))
                stop.complete(Unit)
            }
            assertEquals("1", Files.readString(root.resolve("taps")))
            Files.writeString(root.resolve("taps"), "0")
            val secondStop = CompletableDeferred<Unit>()
            source.record(target, root.resolve("external.mp4").toString(), true, secondStop) {
                Files.writeString(root.resolve("taps"), "null")
                secondStop.complete(Unit)
            }
            assertEquals("null", Files.readString(root.resolve("taps")))
        }
    }

    @Test fun `recording startup failure and cancellation restore taps and publish no video`() = runTest {
        captureFixture { root, source, target ->
            Files.writeString(root.resolve("fail"), "1")
            val video = root.resolve("failed.mp4")
            assertFailsWith<SessionFailure> { source.record(target, video.toString(), true, CompletableDeferred()) {} }
            assertEquals("0", Files.readString(root.resolve("taps")))
            assertFalse(Files.exists(video))
            Files.delete(root.resolve("fail"))
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val job = launch { source.record(target, video.toString(), true, CompletableDeferred()) { ready.complete(Unit) } }
                ready.await(); job.cancelAndJoin()
            }
            assertEquals("0", Files.readString(root.resolve("taps")))
            assertFalse(Files.exists(video))
        }
    }
}

private suspend fun captureFixture(block: suspend (Path, MacEmulatorCapture, RunningEmulator) -> Unit) = withContext(Dispatchers.IO) {
    val root = Files.createTempDirectory("capture-sdk-")
    try {
        val cli = Files.createDirectories(root.resolve("platform-tools")).resolve("adb")
        Files.writeString(root.resolve("taps"), "0")
        Files.writeString(cli, """#!/usr/bin/env python3
import os, sys, pathlib, shutil, base64, signal, time
root = pathlib.Path(__file__).parent.parent
a = sys.argv[3:]
def local(remote): return root / pathlib.Path(remote).name
if a[0] == 'pull': shutil.copyfile(local(a[1]), a[2])
elif a[:2] == ['shell', 'screencap']:
    local(a[-1]).write_bytes(base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII='))
elif a[:2] == ['shell', 'settings']:
    if a[2] == 'get': print((root / 'taps').read_text())
    elif a[2] == 'put': (root / 'taps').write_text(a[-1])
    elif a[2] == 'delete': (root / 'taps').write_text('null')
elif a[:2] == ['shell', 'screenrecord']: pass
elif a[:2] == ['shell', 'sh']:
    remote = a[-1].strip("'").split()[-1]
    if (root / 'fail').exists(): sys.exit(1)
    (root / 'owner').write_text(remote)
    print(os.getpid(), flush=True)
    local(remote).write_bytes(b'\x00\x00\x00\x18ftypmp42' + bytes(64))
    signal.signal(signal.SIGINT, lambda *args: sys.exit(0))
    signal.signal(signal.SIGTERM, lambda *args: sys.exit(0))
    while True: time.sleep(0.05)
elif a[:2] == ['shell', 'cat']:
    sys.stdout.buffer.write(b'screenrecord\x00--time-limit\x00180\x00' + (root / 'owner').read_bytes() + b'\x00')
elif a[:2] == ['shell', 'kill']: os.kill(int(a[-1]), int(a[-2][1:]))
elif a[:2] == ['shell', 'rm']: local(a[-1]).unlink(missing_ok=True)
else: sys.exit(9)
""")
        cli.toFile().setExecutable(true)
        val target = RunningEmulator("emulator-5554", "Fold", "instance")
        block(root, MacEmulatorCapture(root) { listOf(target) }, target)
    } finally { root.toFile().deleteRecursively() }
}
