package dev.picon.androidhub.infrastructure

import com.android.emulator.control.EmulatorControllerGrpc
import com.android.emulator.control.VmRunState
import com.google.protobuf.Empty
import io.grpc.ServerBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmulatorShutdownTest {
    @Test
    fun `shutdown verifies process exit and ignores a stale instance identity`() = runTest {
        val process = ProcessBuilder("/bin/sleep", "30").start()
        val directory = Files.createTempDirectory("hub-shutdown-test")
        val calls = AtomicInteger()
        val service = object : EmulatorControllerGrpc.EmulatorControllerImplBase() {
            override fun setVmState(request: VmRunState, response: StreamObserver<Empty>) {
                assertEquals(VmRunState.RunState.SHUTDOWN, request.state)
                calls.incrementAndGet()
                process.destroy()
                response.onNext(Empty.getDefaultInstance())
                response.onCompleted()
            }
        }
        val server = ServerBuilder.forPort(0).addService(service).build().start()
        val registration = directory.resolve("pid_${process.pid()}.ini")
        try {
            Files.writeString(registration, "port.serial=5558\ngrpc.port=${server.port}\navd.name=Test emulator\n")
            val discovery = MacEmulatorDiscovery(directory)
            val emulator = discovery.discover().single()
            discovery.stop(emulator.copy(instanceId = "previous-process"))
            assertEquals(0, calls.get())
            assertTrue(process.isAlive)
            withContext(Dispatchers.IO) { discovery.stop(emulator) }
            assertEquals(1, calls.get())
            assertFalse(process.isAlive)
            discovery.stop(emulator)
            assertEquals(1, calls.get())
        } finally {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            Files.deleteIfExists(registration)
            Files.deleteIfExists(directory)
        }
    }
}
