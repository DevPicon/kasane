package dev.picon.androidhub.infrastructure

import com.android.emulator.control.EmulatorControllerGrpc
import com.android.emulator.control.Image
import com.android.emulator.control.ImageFormat
import com.android.emulator.control.MouseEvent
import com.android.emulator.control.EmulatorStatus
import com.android.emulator.control.Notification
import com.android.emulator.control.Posture
import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import dev.picon.androidhub.core.RunningEmulator
import dev.picon.androidhub.core.TouchInput
import dev.picon.androidhub.core.FoldPosture
import dev.picon.androidhub.core.ScreenFrame
import io.grpc.ServerBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

class GrpcEmulatorSessionTest {
    @Test
    fun `posture changes with equal geometry release contacts and reject stale input`() = runTest {
        val touches = Collections.synchronizedList(mutableListOf<MouseEvent>())
        val notifications = AtomicReference<StreamObserver<Notification>>()
        val service = object : EmulatorControllerGrpc.EmulatorControllerImplBase() {
            override fun getStatus(request: Empty, response: StreamObserver<EmulatorStatus>) {
                response.onNext(EmulatorStatus.getDefaultInstance())
                response.onCompleted()
            }

            override fun getScreenshot(request: ImageFormat, response: StreamObserver<Image>) {
                response.onNext(Image.newBuilder().setFormat(
                    ImageFormat.newBuilder().setWidth(100).setHeight(200),
                ).build())
                response.onCompleted()
            }

            override fun streamScreenshot(request: ImageFormat, response: StreamObserver<Image>) {
                response.onNext(Image.newBuilder()
                    .setFormat(ImageFormat.newBuilder().setWidth(1).setHeight(2))
                    .setImage(ByteString.copyFrom(ByteArray(8)))
                    .build())
            }

            override fun streamNotification(request: Empty, response: StreamObserver<Notification>) {
                notifications.set(response)
                response.onNext(Notification.newBuilder().setPosture(
                    Posture.newBuilder().setValue(Posture.PostureValue.POSTURE_HALF_OPENED),
                ).build())
            }

            override fun sendMouse(request: MouseEvent, response: StreamObserver<Empty>) {
                touches += request
                response.onNext(Empty.getDefaultInstance())
                response.onCompleted()
            }
        }
        val server = ServerBuilder.forPort(0).addService(service).build().start()
        try {
            GrpcEmulatorSession(EmulatorEndpoint(
                RunningEmulator("test-${System.nanoTime()}", "Test Fold"), server.port, null,
            )).use { session ->
                val frames = Channel<ScreenFrame>(Channel.UNLIMITED)
                val streaming = launch(Dispatchers.Default) { session.frames.collect { frames.send(it) } }
                suspend fun receive(posture: FoldPosture) = withContext(Dispatchers.Default) {
                    withTimeout(5000) { frames.receiveAsFlow().first { it.surface.posture == posture } }
                }
                try {
                    val halfOpen = receive(FoldPosture.HALF_OPEN)
                    session.touch(TouchInput(0.5f, 0.5f, true, halfOpen.surface))
                    notifications.get().onNext(Notification.newBuilder().setPosture(
                        Posture.newBuilder().setValue(Posture.PostureValue.POSTURE_OPENED),
                    ).build())
                    val open = receive(FoldPosture.OPEN)
                    assertEquals(halfOpen.surface.physicalSize, open.surface.physicalSize)
                    assertEquals(listOf(1, 0), touches.map { it.buttons })
                    session.touch(TouchInput(0.8f, 0.8f, true, halfOpen.surface))
                    assertEquals(2, touches.size)
                    session.touch(TouchInput(0.8f, 0.8f, true, open.surface))
                    assertEquals(3, touches.size)
                    session.releaseInput()
                } finally {
                    streaming.cancelAndJoin()
                    frames.close()
                }
            }
        } finally {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `release at a new position sends final movement before lifting the contact`() = runTest {
        val touches = Collections.synchronizedList(mutableListOf<MouseEvent>())
        val service = object : EmulatorControllerGrpc.EmulatorControllerImplBase() {
            override fun getStatus(request: Empty, response: StreamObserver<EmulatorStatus>) {
                response.onNext(EmulatorStatus.getDefaultInstance())
                response.onCompleted()
            }
            override fun getScreenshot(request: ImageFormat, response: StreamObserver<Image>) {
                response.onNext(Image.newBuilder().setFormat(
                    ImageFormat.newBuilder().setWidth(100).setHeight(200),
                ).build())
                response.onCompleted()
            }

            override fun streamScreenshot(request: ImageFormat, response: StreamObserver<Image>) {
                response.onNext(Image.newBuilder()
                    .setFormat(ImageFormat.newBuilder().setWidth(1).setHeight(2))
                    .setImage(ByteString.copyFrom(ByteArray(8)))
                    .build())
            }

            override fun sendMouse(request: MouseEvent, response: StreamObserver<Empty>) {
                touches += request
                response.onNext(Empty.getDefaultInstance())
                response.onCompleted()
            }
        }
        val server = ServerBuilder.forPort(0).addService(service).build().start()
        try {
            GrpcEmulatorSession(EmulatorEndpoint(
                RunningEmulator("test-${System.nanoTime()}", "Test AVD"), server.port, null,
            )).use { session ->
                session.frames.first()
                session.touch(TouchInput(0.5f, 0.8f, true))
                session.touch(TouchInput(0.5f, 0.2f, false))
                assertEquals(listOf(1, 1, 0), touches.map { it.buttons })
                assertEquals(listOf(159, 39, 39), touches.map { it.y })
                assertEquals(listOf(49, 49, 49), touches.map { it.x })
            }
        } finally {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
