package dev.picon.androidhub.infrastructure

import com.android.emulator.control.*
import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import dev.picon.androidhub.core.*
import io.grpc.ServerBuilder
import io.grpc.Status
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MacTransportDiagnosticsTest {
    @Test fun `read only probe succeeds while the screen session owns its lock and never exposes secrets`() = runTest {
        fixture { sdk, endpoint ->
            GrpcEmulatorSession(endpoint).use { session ->
                session.frames.first()
                val progress = mutableListOf<DiagnosticCheck>()
                val report = inspector(sdk, endpoint).inspect(device(endpoint), progress::add)
                assertTrue(report.healthy)
                assertEquals(DiagnosticStage.entries, progress.map { it.stage })
                assertFalse(report.toString().contains(secret))
                assertFalse(report.toString().contains(endpoint.port.toString()))
            }
        }
    }

    @Test fun `offline exited missing and replacement endpoints are classified without connecting`() = runTest {
        fixture { sdk, endpoint ->
            val target = device(endpoint)
            assertEquals(TransportIssue.OFFLINE, inspector(sdk, endpoint).inspect(target.copy(running = null)) {}.issue)
            assertEquals(TransportIssue.PROCESS_EXITED, MacTransportDiagnostics(sdk, { listOf(endpoint) }, { false }).inspect(target) {}.issue)
            assertEquals(TransportIssue.ENDPOINT_MISSING, MacTransportDiagnostics(sdk, { emptyList() }, { true }).inspect(target) {}.issue)
            val replacement = EmulatorEndpoint(endpoint.emulator.copy(instanceId = "new-instance"), endpoint.port, secret)
            assertEquals(TransportIssue.INSTANCE_CHANGED, MacTransportDiagnostics(sdk, { listOf(replacement) }, { true }).inspect(target) {}.issue)
            val missingToken = EmulatorEndpoint(endpoint.emulator, endpoint.port, null)
            assertEquals(TransportIssue.AUTHENTICATION, inspector(sdk, missingToken).inspect(target) {}.issue)
        }
    }

    @Test fun `ADB missing and unavailable are reported independently of working video`() = runTest {
        fixture { sdk, endpoint ->
            val adb = sdk.resolve("platform-tools/adb")
            Files.delete(adb)
            val missing = inspector(sdk, endpoint).inspect(device(endpoint)) {}
            assertEquals(TransportIssue.ADB_MISSING, missing.issue)
            assertEquals(DiagnosticOutcome.PASS, missing.checks.last().outcome)
            Files.writeString(adb, "#!/bin/sh\nexit 1\n")
            check(adb.toFile().setExecutable(true))
            assertEquals(TransportIssue.ADB_UNAVAILABLE, inspector(sdk, endpoint).inspect(device(endpoint)) {}.issue)
        }
    }

    @Test fun `authentication and protocol errors discard arbitrary server messages`() = runTest {
        fixture(Mode.AUTH) { sdk, endpoint ->
            val report = inspector(sdk, endpoint).inspect(device(endpoint)) {}
            assertEquals(TransportIssue.AUTHENTICATION, report.issue)
            assertFalse(report.toString().contains(secret))
            assertEquals(DiagnosticOutcome.SKIPPED, report.checks.last().outcome)
        }
        fixture(Mode.UNSUPPORTED) { sdk, endpoint ->
            assertEquals(TransportIssue.PROTOCOL_UNSUPPORTED, inspector(sdk, endpoint).inspect(device(endpoint)) {}.issue)
        }
    }

    @Test fun `status timeout and absent or invalid first frames cannot report success`() = runTest {
        fixture(Mode.STATUS_TIMEOUT) { sdk, endpoint ->
            assertEquals(TransportIssue.RPC_TIMEOUT, inspector(sdk, endpoint, 150).inspect(device(endpoint)) {}.issue)
        }
        for (mode in listOf(Mode.NO_FRAME, Mode.INVALID_FRAME)) fixture(mode) { sdk, endpoint ->
            assertEquals(TransportIssue.FRAME_TIMEOUT, inspector(sdk, endpoint, 150).inspect(device(endpoint)) {}.issue)
        }
        fixture(Mode.EMPTY_STREAM) { sdk, endpoint ->
            assertEquals(TransportIssue.FRAME_MISSING, inspector(sdk, endpoint).inspect(device(endpoint)) {}.issue)
        }
    }

    @Test fun `cancelling a waiting probe closes owned work without a final report`() = runTest {
        fixture(Mode.NO_FRAME) { sdk, endpoint ->
            val waiting = CompletableDeferred<Unit>()
            var finished = false
            coroutineScope {
                val job = launch(Dispatchers.IO) {
                    inspector(sdk, endpoint).inspect(device(endpoint)) { if (it.stage == DiagnosticStage.GRPC) waiting.complete(Unit) }
                    finished = true
                }
                withTimeout(5000) { waiting.await() }
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
                assertFalse(finished)
            }
        }
    }
}

private const val secret = "diagnostic-secret-never-log"
private enum class Mode { OK, AUTH, UNSUPPORTED, STATUS_TIMEOUT, NO_FRAME, INVALID_FRAME, EMPTY_STREAM }
private fun device(endpoint: EmulatorEndpoint) = AvailableEmulator("Test", "Test", "36", DeviceStatus.READY, endpoint.emulator)
private fun inspector(sdk: Path, endpoint: EmulatorEndpoint, timeout: Long = 3000) = MacTransportDiagnostics(sdk, { listOf(endpoint) }, { true }, timeout)

private suspend fun fixture(mode: Mode = Mode.OK, block: suspend (Path, EmulatorEndpoint) -> Unit) = withContext(Dispatchers.IO) {
    val sdk = Files.createTempDirectory("hub-diagnostic-test")
    val adb = sdk.resolve("platform-tools/adb")
    Files.createDirectories(adb.parent)
    Files.writeString(adb, "#!/bin/sh\necho device\n")
    check(adb.toFile().setExecutable(true))
    fun image() = Image.newBuilder().setFormat(ImageFormat.newBuilder().setWidth(1).setHeight(2))
        .setImage(ByteString.copyFrom(ByteArray(if (mode == Mode.INVALID_FRAME) 1 else 8))).build()
    val service = object : EmulatorControllerGrpc.EmulatorControllerImplBase() {
        override fun getStatus(request: Empty, response: StreamObserver<EmulatorStatus>) {
            when (mode) {
                Mode.AUTH -> response.onError(Status.UNAUTHENTICATED.withDescription(secret).asRuntimeException())
                Mode.UNSUPPORTED -> response.onError(Status.UNIMPLEMENTED.withDescription(secret).asRuntimeException())
                Mode.STATUS_TIMEOUT -> Unit
                else -> { response.onNext(EmulatorStatus.getDefaultInstance()); response.onCompleted() }
            }
        }
        override fun getScreenshot(request: ImageFormat, response: StreamObserver<Image>) { response.onNext(image()); response.onCompleted() }
        override fun streamScreenshot(request: ImageFormat, response: StreamObserver<Image>) {
            when (mode) {
                Mode.NO_FRAME -> Unit
                Mode.EMPTY_STREAM -> response.onCompleted()
                else -> response.onNext(image())
            }
        }
    }
    val server = ServerBuilder.forPort(0).addService(service).build().start()
    try { block(sdk, EmulatorEndpoint(RunningEmulator("test-${System.nanoTime()}", "Test", "instance", "Test"), server.port, secret)) }
    finally { server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); sdk.toFile().deleteRecursively() }
}
