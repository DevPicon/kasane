package dev.picon.androidhub.infrastructure

import com.android.emulator.control.*
import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import dev.picon.androidhub.core.*
import io.grpc.ServerBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.*

class EmulatorControlsTest {
    @Test
    fun `temporary framebuffer failure recovers without losing the session`() = runTest {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val service = object : EmulatorControllerGrpc.EmulatorControllerImplBase() {
            override fun getStatus(request: Empty, response: StreamObserver<EmulatorStatus>) {
                response.onNext(EmulatorStatus.getDefaultInstance()); response.onCompleted()
            }
            override fun getScreenshot(request: ImageFormat, response: StreamObserver<Image>) {
                if (attempts.incrementAndGet() < 3) {
                    response.onError(io.grpc.Status.FAILED_PRECONDITION.asRuntimeException())
                } else {
                    response.onNext(Image.newBuilder().setFormat(ImageFormat.newBuilder().setWidth(1).setHeight(2)).build())
                    response.onCompleted()
                }
            }
            override fun streamScreenshot(request: ImageFormat, response: StreamObserver<Image>) {
                response.onNext(Image.newBuilder().setFormat(ImageFormat.newBuilder().setWidth(1).setHeight(2))
                    .setImage(ByteString.copyFrom(ByteArray(8))).build())
            }
        }
        val server = ServerBuilder.forPort(0).addService(service).build().start()
        try {
            GrpcEmulatorSession(EmulatorEndpoint(RunningEmulator("retry-${System.nanoTime()}", "Test"), server.port, null)).use { session ->
                val frame = withContext(Dispatchers.Default) { withTimeout(5000) { session.frames.first() } }
                assertEquals(DisplaySize(1, 2), frame.size)
                assertEquals(3, attempts.get())
            }
        } finally { server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS) }
    }

    @Test
    fun `controls send navigation posture and rotation RPCs with surface protection`() = runTest {
        val keys = Collections.synchronizedList(mutableListOf<String>())
        val rotations = Collections.synchronizedList(mutableListOf<Float>())
        val postures = Collections.synchronizedList(mutableListOf<Posture.PostureValue>())
        val service = object : EmulatorControllerGrpc.EmulatorControllerImplBase() {
            override fun getStatus(request: Empty, response: StreamObserver<EmulatorStatus>) {
                response.onNext(EmulatorStatus.newBuilder().setHardwareConfig(EntryList.newBuilder().addAllEntry(
                    mapOf("hw.accelerometer" to "yes", "hw.sensor.hinge" to "yes", "hw.sensor.posture_list" to "1,3")
                        .map { (key, value) -> Entry.newBuilder().setKey(key).setValue(value).build() },
                )).build())
                response.onCompleted()
            }
            override fun getPhysicalModel(request: PhysicalModelValue, response: StreamObserver<PhysicalModelValue>) {
                response.onNext(PhysicalModelValue.newBuilder().setStatus(PhysicalModelValue.State.OK).build())
                response.onCompleted()
            }
            override fun getScreenshot(request: ImageFormat, response: StreamObserver<Image>) {
                response.onNext(Image.newBuilder().setFormat(ImageFormat.newBuilder().setWidth(100).setHeight(200)).build())
                response.onCompleted()
            }
            override fun streamScreenshot(request: ImageFormat, response: StreamObserver<Image>) {
                response.onNext(Image.newBuilder().setFormat(ImageFormat.newBuilder().setWidth(1).setHeight(2))
                    .setImage(ByteString.copyFrom(ByteArray(8))).build())
            }
            override fun sendKey(request: KeyboardEvent, response: StreamObserver<Empty>) {
                keys += request.key
                response.onNext(Empty.getDefaultInstance()); response.onCompleted()
            }
            override fun setPosture(request: Posture, response: StreamObserver<Empty>) {
                postures += request.value
                response.onNext(Empty.getDefaultInstance()); response.onCompleted()
            }
            override fun setPhysicalModel(request: PhysicalModelValue, response: StreamObserver<Empty>) {
                assertEquals(PhysicalModelValue.PhysicalType.ROTATION, request.target)
                rotations += request.value.dataList[2]
                response.onNext(Empty.getDefaultInstance()); response.onCompleted()
            }
        }
        val server = ServerBuilder.forPort(0).addService(service).build().start()
        try {
            GrpcEmulatorSession(EmulatorEndpoint(RunningEmulator("control-${System.nanoTime()}", "Unnamed device"), server.port, null)).use { session ->
                val capabilities = session.capabilities()
                assertEquals(setOf(FoldPosture.FOLDED, FoldPosture.OPEN), capabilities.postures)
                val frames = Channel<ScreenFrame>(Channel.UNLIMITED)
                val streaming = launch(Dispatchers.Default) { session.frames.collect { frames.send(it) } }
                try {
                    val frame = withContext(Dispatchers.Default) { withTimeout(5000) { frames.receive() } }
                    for (command in listOf(EmulatorCommand.Back, EmulatorCommand.Home, EmulatorCommand.Recents)) session.execute(command, frame.surface)
                    session.execute(EmulatorCommand.SetPosture(FoldPosture.FOLDED), frame.surface)
                    session.execute(EmulatorCommand.Rotate, frame.surface)
                    session.execute(EmulatorCommand.Rotate, frame.surface.copy(rotation = 1))
                    assertFailsWith<SessionFailure> { session.execute(EmulatorCommand.SetPosture(FoldPosture.HALF_OPEN), frame.surface) }
                    assertEquals(listOf("GoBack", "GoHome", "AppSwitch"), keys)
                    assertEquals(listOf(Posture.PostureValue.POSTURE_CLOSED), postures)
                    assertEquals(listOf(90f), rotations)
                } finally { streaming.cancelAndJoin(); frames.close() }
            }
        } finally { server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS) }
    }
}
