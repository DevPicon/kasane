package dev.picon.androidhub.infrastructure

import com.android.emulator.control.EmulatorControllerGrpcKt
import com.android.emulator.control.ImageFormat
import com.android.emulator.control.KeyboardEvent
import com.android.emulator.control.MouseEvent
import com.android.emulator.control.Posture
import com.android.emulator.control.PhysicalModelValue
import com.android.emulator.control.ParameterValue
import com.android.emulator.control.VmRunState
import com.google.protobuf.Empty
import dev.picon.androidhub.core.DisplaySurface
import dev.picon.androidhub.core.FoldPosture
import dev.picon.androidhub.core.DisplaySize
import dev.picon.androidhub.core.EmulatorSession
import dev.picon.androidhub.core.ScreenFrame
import dev.picon.androidhub.core.TouchInput
import dev.picon.androidhub.core.SessionFailure
import dev.picon.androidhub.core.EmulatorCapabilities
import dev.picon.androidhub.core.EmulatorCommand
import io.grpc.Metadata
import io.grpc.StatusException
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.MetadataUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class GrpcEmulatorSession(endpoint: EmulatorEndpoint) : EmulatorSession {
    override val emulator = endpoint.emulator
    private val channel = NettyChannelBuilder.forAddress("127.0.0.1", endpoint.port)
        .usePlaintext()
        .maxInboundMessageSize(32 * 1024 * 1024)
        .build()
    private val sessionLock = try {
        acquireSessionLock(emulator.serial)
    } catch (error: Exception) {
        channel.shutdownNow()
        throw error
    }
    private val metadata = Metadata().apply {
        endpoint.token?.let { put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer $it") }
    }
    private val stub = EmulatorControllerGrpcKt.EmulatorControllerCoroutineStub(channel)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
    private val inputMutex = Mutex()
    private var currentSurface: DisplaySurface? = null
    private var lastTouch: TouchInput? = null
    private var controls = EmulatorCapabilities()

    override suspend fun capabilities(): EmulatorCapabilities {
        val status = stub.withDeadlineAfter(5, TimeUnit.SECONDS).getStatus(Empty.getDefaultInstance())
        val hardware = status.hardwareConfig.entryList.associate { it.key to it.value }
        controls = hardwareCapabilities(hardware)
        if (controls.rotation) {
            val available = try {
                stub.withDeadlineAfter(2, TimeUnit.SECONDS).getPhysicalModel(
                    PhysicalModelValue.newBuilder().setTarget(PhysicalModelValue.PhysicalType.ROTATION).build(),
                ).status == PhysicalModelValue.State.OK
            } catch (error: StatusException) {
                if (error.status.code == io.grpc.Status.Code.UNIMPLEMENTED) false else throw error
            }
            controls = controls.copy(rotation = available)
        }
        return controls
    }

    override suspend fun execute(command: EmulatorCommand, surface: DisplaySurface) {
        if (!controls.supports(command)) throw SessionFailure("Esta acción no está disponible en el dispositivo seleccionado.")
        when (command) {
            EmulatorCommand.Back -> key("GoBack", surface)
            EmulatorCommand.Home -> key("GoHome", surface)
            EmulatorCommand.Recents -> key("AppSwitch", surface)
            else -> inputMutex.withLock {
                if (surface != currentSurface) return@withLock
                lastTouch?.let { sendPointer(it.copy(pressed = false)) }
                lastTouch = null
                when (command) {
                    EmulatorCommand.Rotate -> {
                        val angle = ((surface.rotation + 1) % 4) * 90f
                        stub.withDeadlineAfter(2, TimeUnit.SECONDS).setPhysicalModel(
                            PhysicalModelValue.newBuilder().setTarget(PhysicalModelValue.PhysicalType.ROTATION)
                                .setValue(ParameterValue.newBuilder().addAllData(listOf(0f, 0f, angle))).build(),
                        )
                    }
                    is EmulatorCommand.SetPosture -> stub.withDeadlineAfter(2, TimeUnit.SECONDS).setPosture(
                        Posture.newBuilder().setValue(command.posture.toProtoPosture()).build(),
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val frames: Flow<ScreenFrame> = flow {
        val status = stub.withDeadlineAfter(5, TimeUnit.SECONDS).getStatus(Empty.getDefaultInstance())
        val hardware = status.hardwareConfig.entryList.associate { it.key to it.value }
        val postures = stub.streamNotification(Empty.getDefaultInstance())
            .filter { it.hasPosture() }
            .map { it.posture.value.toFoldPosture() }
            .onStart { emit(null) }
            .catch { error ->
                if (error is StatusException && error.status.code == io.grpc.Status.Code.UNIMPLEMENTED) emit(null)
                else throw error
            }
            .distinctUntilChanged()
        emitAll(postures.flatMapLatest { posture ->
            flow {
                // Stop old contacts before any new physical coordinate system is installed.
                withContext(NonCancellable) { releaseInput() }
                inputMutex.withLock { currentSurface = null }
                val sizePrefix = if (posture == FoldPosture.FOLDED) "hw.displayRegion.0.1" else "hw.lcd"
                val expectedWidth = hardware["$sizePrefix.width"]?.toIntOrNull()
                val expectedHeight = hardware["$sizePrefix.height"]?.toIntOrNull()
                val expected = if (hardwareCapabilities(hardware).postures.isNotEmpty() && expectedWidth != null && expectedHeight != null && expectedWidth > 0 && expectedHeight > 0) {
                    DisplaySize(expectedWidth, expectedHeight)
                } else null
                var full = captureSurface(posture, expected)
                var previousSize: DisplaySize? = null
                val format = ImageFormat.newBuilder()
                    .setFormat(ImageFormat.ImgFormat.RGBA8888)
                    .setWidth(720)
                    .setHeight(1280)
                    .build()
                stub.streamScreenshot(format).collect { image ->
                    val width = image.format.width
                    val height = image.format.height
                    if (width > 0 && height > 0 && image.image.size().toLong() == width.toLong() * height * 4) {
                        val size = DisplaySize(width, height)
                        if ((previousSize != null && size != previousSize) || image.format.rotation.rotationValue != full.rotation) {
                            withContext(NonCancellable) { releaseInput() }
                            full = captureSurface(posture, expected)
                        }
                        previousSize = size
                        if (full.rotation != image.format.rotation.rotationValue ||
                            kotlin.math.abs(size.width.toFloat() / size.height - full.physicalSize.width.toFloat() / full.physicalSize.height) > .01f) return@collect
                        inputMutex.withLock { currentSurface = full }
                        emit(ScreenFrame(emulator.serial, size, image.image.toByteArray(), image.timestampUs, full))
                    }
                }
                error("El emulador cerró la conexión de pantalla.")
            }
        })
    }.retryWhen { error, attempt ->
        // Display reconfiguration can temporarily invalidate the framebuffer RPC.
        // Re-subscribe to the current posture and geometry before accepting new input.
        val transient = (error as? StatusException)?.status?.code in setOf(
            io.grpc.Status.Code.FAILED_PRECONDITION, io.grpc.Status.Code.INTERNAL,
            io.grpc.Status.Code.UNKNOWN, io.grpc.Status.Code.CANCELLED, io.grpc.Status.Code.DEADLINE_EXCEEDED,
        )
        if (transient && attempt < 2) {
            withContext(NonCancellable) { releaseInput() }
            inputMutex.withLock { currentSurface = null }
            delay(400 * (attempt + 1))
            true
        } else false
    }.catch { error ->
        if (error is StatusException) throw SessionFailure(connectionMessage(error), error)
        throw error
    }.flowOn(Dispatchers.IO)

    private suspend fun captureSurface(posture: FoldPosture?, expected: DisplaySize?): DisplaySurface = withTimeoutOrNull(5000) {
        while (true) {
            val image = try {
                stub.withDeadlineAfter(2, TimeUnit.SECONDS).getScreenshot(
                    ImageFormat.newBuilder().setFormat(ImageFormat.ImgFormat.RGBA8888).build(),
                )
            } catch (error: StatusException) {
                // A new panel can exist before Android posts its first framebuffer.
                if (error.status.code != io.grpc.Status.Code.FAILED_PRECONDITION) throw error
                delay(100)
                continue
            }
            val format = image.format
            val expectedGeometry = expected == null ||
                (format.width == expected.width && format.height == expected.height) ||
                (format.width == expected.height && format.height == expected.width)
            if (format.width > 0 && format.height > 0 && expectedGeometry) {
                // Keep the logical display requested by the screenshot API. The emulator's mouse
                // agent maps primary-display input to the active Pixel Fold panel.
                return@withTimeoutOrNull DisplaySurface(DisplaySize(format.width, format.height), format.display, posture, format.rotation.rotationValue)
            }
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE")
        error("La pantalla todavía no está disponible.")
    } ?: throw SessionFailure("Android todavía no ha publicado la pantalla del dispositivo. Puedes volver a conectar.")

    override suspend fun touch(input: TouchInput) = inputMutex.withLock {
        val surface = currentSurface ?: return@withLock
        if (input.surface != null && input.surface != surface) return@withLock
        val previous = lastTouch
        if (!input.pressed && previous != null && (input.x != previous.x || input.y != previous.y)) {
            // Some hosts report the final pointer movement only together with release.
            sendPointer(input.copy(pressed = true))
        }
        if (input.pressed) lastTouch = input
        sendPointer(input)
        if (!input.pressed) lastTouch = null
    }

    private suspend fun sendPointer(input: TouchInput) {
        val surface = currentSurface ?: return
        val (x, y) = pointerCoordinates(input.x, input.y, surface)
        val pointer = MouseEvent.newBuilder()
            .setX(x)
            .setY(y)
            .setButtons(if (input.pressed) 1 else 0)
            .setDisplay(surface.displayId)
        stub.withDeadlineAfter(2, TimeUnit.SECONDS).sendMouse(pointer.build())
    }

    override suspend fun key(key: String, surface: DisplaySurface?) {
        inputMutex.withLock {
            if (surface != null && surface != currentSurface) return@withLock
            stub.withDeadlineAfter(2, TimeUnit.SECONDS).sendKey(
                KeyboardEvent.newBuilder().setKey(key).setEventType(KeyboardEvent.KeyEventType.keypress).build(),
            )
        }
    }

    override suspend fun text(text: String, surface: DisplaySurface?) {
        inputMutex.withLock {
            if (surface != null && surface != currentSurface) return@withLock
            stub.withDeadlineAfter(2, TimeUnit.SECONDS).sendKey(KeyboardEvent.newBuilder().setText(text).build())
        }
    }

    override suspend fun releaseInput() = inputMutex.withLock {
        lastTouch?.let { sendPointer(it.copy(pressed = false)) }
        lastTouch = null
    }

    override fun close() {
        channel.shutdownNow()
        sessionLock.channel().close()
    }

    suspend fun shutdown() {
        stub.withDeadlineAfter(5, TimeUnit.SECONDS).setVmState(
            VmRunState.newBuilder().setState(VmRunState.RunState.SHUTDOWN).build(),
        )
    }
}

/** Prevent the diagnostic client and another hub process from competing for the same stream. */
internal fun acquireSessionLock(serial: String): FileLock {
    val path = Path.of(System.getProperty("java.io.tmpdir"), "android-hub-$serial.lock")
    val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    try {
        val lock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        return lock ?: throw SessionFailure("Otro proceso de Android Hub está usando esta instancia. Ciérralo antes de conectar.")
    } catch (error: Exception) {
        channel.close()
        throw error
    }
}

internal fun connectionMessage(error: Exception): String = when ((error as? StatusException)?.status?.code) {
    io.grpc.Status.Code.UNAUTHENTICATED, io.grpc.Status.Code.PERMISSION_DENIED ->
        "El emulador rechazó la autenticación gRPC. Esta configuración necesita una integración compatible."
    io.grpc.Status.Code.UNAVAILABLE -> "Se perdió la conexión con el emulador. Puedes volver a conectar."
    else -> "No se pudo completar la conexión con el emulador (${(error as? StatusException)?.status?.code ?: error.javaClass.simpleName})."
}

internal fun Posture.PostureValue.toFoldPosture(): FoldPosture? = when (this) {
    Posture.PostureValue.POSTURE_CLOSED -> FoldPosture.FOLDED
    Posture.PostureValue.POSTURE_HALF_OPENED -> FoldPosture.HALF_OPEN
    Posture.PostureValue.POSTURE_OPENED -> FoldPosture.OPEN
    Posture.PostureValue.POSTURE_FLIPPED -> FoldPosture.FLIPPED
    Posture.PostureValue.POSTURE_TENT -> FoldPosture.TENT
    else -> null
}

internal fun FoldPosture.toProtoPosture(): Posture.PostureValue = when (this) {
    FoldPosture.FOLDED -> Posture.PostureValue.POSTURE_CLOSED
    FoldPosture.HALF_OPEN -> Posture.PostureValue.POSTURE_HALF_OPENED
    FoldPosture.OPEN -> Posture.PostureValue.POSTURE_OPENED
    FoldPosture.FLIPPED -> Posture.PostureValue.POSTURE_FLIPPED
    FoldPosture.TENT -> Posture.PostureValue.POSTURE_TENT
}

/** Runtime hardware declaration, independent of AVD name and device model. */
internal fun hardwareCapabilities(hardware: Map<String, String>): EmulatorCapabilities = EmulatorCapabilities(
    navigation = true,
    rotation = hardware["hw.accelerometer"].isHardwareEnabled(),
    postures = if (hardware["hw.sensor.hinge"].isHardwareEnabled() || hardware["hw.sensor.roll"].isHardwareEnabled()) {
        hardware["hw.sensor.posture_list"].orEmpty().split(',').mapNotNull {
            it.trim().toIntOrNull()?.let(Posture.PostureValue::forNumber)?.toFoldPosture()
        }.toSet()
    } else emptySet(),
)

private fun String?.isHardwareEnabled() = this?.lowercase() in setOf("yes", "true", "1")

/** Screenshot pixels are rotated; the mouse agent consumes the panel's natural coordinates. */
internal fun pointerCoordinates(x: Float, y: Float, surface: DisplaySurface): Pair<Int, Int> {
    val (naturalX, naturalY) = when (surface.rotation) {
        1 -> 1f - y to x
        2 -> 1f - x to 1f - y
        3 -> y to 1f - x
        else -> x to y
    }
    val size = surface.physicalSize
    val swapped = surface.rotation % 2 == 1
    val width = if (swapped) size.height else size.width
    val height = if (swapped) size.width else size.height
    return (naturalX * (width - 1)).toInt() to (naturalY * (height - 1)).toInt()
}
