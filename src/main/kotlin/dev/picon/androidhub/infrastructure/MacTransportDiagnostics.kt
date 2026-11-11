package dev.picon.androidhub.infrastructure

import com.android.emulator.control.EmulatorControllerGrpcKt
import com.android.emulator.control.ImageFormat
import com.google.protobuf.Empty
import dev.picon.androidhub.core.*
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.MetadataUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** A read-only probe; it neither takes the input-session lock nor records image contents. */
internal class MacTransportDiagnostics(
    private val sdk: Path = Path.of(System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME") ?: "${System.getProperty("user.home")}/Library/Android/sdk"),
    private val endpoints: suspend () -> List<EmulatorEndpoint> = { MacEmulatorDiscovery().endpoints() },
    private val processAlive: (RunningEmulator) -> Boolean = ::diagnosticProcessAlive,
    private val rpcTimeoutMs: Long = 5000,
) : TransportDiagnostics {
    override suspend fun inspect(device: AvailableEmulator, onCheck: (DiagnosticCheck) -> Unit): TransportReport = withContext(Dispatchers.IO) {
        val checks = mutableListOf<DiagnosticCheck>()
        var issue: TransportIssue? = null
        var began = System.nanoTime()
        fun add(stage: DiagnosticStage, outcome: DiagnosticOutcome, detail: String) {
            val now = System.nanoTime()
            DiagnosticCheck(stage, outcome, detail, (now - began) / 1_000_000).also { checks += it; onCheck(it) }
            began = now
        }
        fun fail(stage: DiagnosticStage, reason: TransportIssue, detail: String) {
            if (issue == null) issue = reason
            add(stage, DiagnosticOutcome.FAIL, detail)
        }
        fun finish(): TransportReport {
            DiagnosticStage.entries.filter { stage -> checks.none { it.stage == stage } }.forEach {
                add(it, DiagnosticOutcome.SKIPPED, "No comprobado: falta un requisito anterior.")
            }
            return TransportReport(checks.toList(), issue, diagnosticSuggestion(issue))
        }
        val expected = device.running
        if (expected == null) {
            fail(DiagnosticStage.PROCESS, TransportIssue.OFFLINE, "El AVD está apagado.")
            return@withContext finish()
        }
        if (!processAlive(expected)) {
            fail(DiagnosticStage.PROCESS, TransportIssue.PROCESS_EXITED, "La instancia identificada ya no está activa.")
            return@withContext finish()
        }
        add(DiagnosticStage.PROCESS, DiagnosticOutcome.PASS, "Proceso activo; identidad verificada.")
        val adb = sdk.resolve("platform-tools/adb")
        if (!Files.isExecutable(adb)) fail(DiagnosticStage.ADB, TransportIssue.ADB_MISSING, "No se encontró ADB en el SDK configurado.")
        else try {
            val state = SdkToolProcess(adb).run(listOf("-s", expected.serial, "get-state"), timeoutMs = 3000).trim()
            if (state == "device") add(DiagnosticStage.ADB, DiagnosticOutcome.PASS, "ADB reconoce la instancia.")
            else fail(DiagnosticStage.ADB, TransportIssue.ADB_UNAVAILABLE, "ADB aún no reconoce una conexión disponible.")
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { fail(DiagnosticStage.ADB, TransportIssue.ADB_UNAVAILABLE, "ADB no pudo comunicarse con la instancia dentro del plazo.") }

        val available = endpoints()
        val endpoint = available.firstOrNull { it.emulator.instanceId == expected.instanceId && it.emulator.serial == expected.serial }
        if (endpoint == null) {
            val changed = available.any { it.emulator.avdId == expected.avdId || it.emulator.serial == expected.serial }
            fail(DiagnosticStage.ENDPOINT, if (changed) TransportIssue.INSTANCE_CHANGED else TransportIssue.ENDPOINT_MISSING,
                if (changed) "La instancia cambió durante la comprobación." else "El proceso no publica todavía un endpoint gRPC local.")
            return@withContext finish()
        }
        if (endpoint.token.isNullOrBlank()) {
            fail(DiagnosticStage.ENDPOINT, TransportIssue.AUTHENTICATION, "El endpoint no publica las credenciales esperadas para el transporte autenticado.")
            return@withContext finish()
        }
        add(DiagnosticStage.ENDPOINT, DiagnosticOutcome.PASS, "Endpoint local con autenticación disponible.")
        val channel = NettyChannelBuilder.forAddress("127.0.0.1", endpoint.port).usePlaintext().maxInboundMessageSize(32 * 1024 * 1024).build()
        try {
            val metadata = Metadata().apply { put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer ${endpoint.token}") }
            val stub = EmulatorControllerGrpcKt.EmulatorControllerCoroutineStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
            try {
                val status = withTimeoutOrNull(rpcTimeoutMs + 1000) { stub.withDeadlineAfter(rpcTimeoutMs, TimeUnit.MILLISECONDS).getStatus(Empty.getDefaultInstance()) }
                if (status == null) {
                    fail(DiagnosticStage.GRPC, TransportIssue.RPC_TIMEOUT, "El servicio gRPC no respondió dentro del plazo.")
                    return@withContext finish()
                }
                add(DiagnosticStage.GRPC, DiagnosticOutcome.PASS, "El servicio gRPC respondió a la consulta de estado.")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                fail(DiagnosticStage.GRPC, diagnosticRpcIssue(error), diagnosticRpcDetail(error))
                return@withContext finish()
            }
            try {
                val frame = withTimeoutOrNull(rpcTimeoutMs) {
                    stub.streamScreenshot(ImageFormat.newBuilder().setFormat(ImageFormat.ImgFormat.RGBA8888).setWidth(320).setHeight(640).build()).first { image ->
                        val width = image.format.width
                        val height = image.format.height
                        width > 0 && height > 0 && image.image.size().toLong() == width.toLong() * height * 4
                    }
                }
                if (frame == null) fail(DiagnosticStage.FIRST_FRAME, TransportIssue.FRAME_TIMEOUT, "No llegó un frame válido dentro del plazo.")
                else if (!processAlive(expected)) fail(DiagnosticStage.FIRST_FRAME, TransportIssue.INSTANCE_CHANGED, "La instancia terminó durante la comprobación.")
                else add(DiagnosticStage.FIRST_FRAME, DiagnosticOutcome.PASS, "Primer frame válido recibido; su contenido no se conserva en el informe.")
            } catch (error: CancellationException) { throw error }
            catch (_: NoSuchElementException) { fail(DiagnosticStage.FIRST_FRAME, TransportIssue.FRAME_MISSING, "El stream terminó sin entregar un frame válido.") }
            catch (error: Exception) { fail(DiagnosticStage.FIRST_FRAME, diagnosticRpcIssue(error), diagnosticRpcDetail(error)) }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS) }
        }
        finish()
    }
}

private fun diagnosticProcessAlive(emulator: RunningEmulator): Boolean {
    val pid = emulator.instanceId.substringBefore('@').toLongOrNull() ?: return false
    val handle = ProcessHandle.of(pid).orElse(null) ?: return false
    return handle.isAlive && "$pid@${handle.info().startInstant().orElse(null)}" == emulator.instanceId
}

private fun diagnosticRpcIssue(error: Exception): TransportIssue = when (Status.fromThrowable(error).code) {
    Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED -> TransportIssue.AUTHENTICATION
    Status.Code.UNAVAILABLE -> TransportIssue.UNREACHABLE
    Status.Code.UNIMPLEMENTED -> TransportIssue.PROTOCOL_UNSUPPORTED
    Status.Code.DEADLINE_EXCEEDED -> TransportIssue.RPC_TIMEOUT
    else -> TransportIssue.UNKNOWN
}

private fun diagnosticRpcDetail(error: Exception): String = when (diagnosticRpcIssue(error)) {
    TransportIssue.AUTHENTICATION -> "El servicio rechazó la autenticación. No se muestran credenciales."
    TransportIssue.UNREACHABLE -> "No se pudo establecer la conexión gRPC local."
    TransportIssue.PROTOCOL_UNSUPPORTED -> "El servicio no implementa el protocolo solicitado."
    TransportIssue.RPC_TIMEOUT -> "La consulta gRPC superó su plazo."
    else -> "Falló la comprobación del transporte. No se incluyen mensajes internos del servidor."
}

internal fun diagnosticSuggestion(issue: TransportIssue?): String = when (issue) {
    null -> "El transporte respondió correctamente. Si la pantalla sigue sin responder, prueba Reconectar pantalla."
    TransportIssue.OFFLINE -> "Inicia el emulador y vuelve a comprobar."
    TransportIssue.PROCESS_EXITED, TransportIssue.INSTANCE_CHANGED -> "Actualiza el inventario y selecciona la instancia actual."
    TransportIssue.ADB_MISSING -> "Instala Platform Tools o revisa el SDK configurado."
    TransportIssue.ADB_UNAVAILABLE -> "Espera a que Android termine de arrancar y comprueba ADB antes de reintentar."
    TransportIssue.ENDPOINT_MISSING -> "Espera el arranque. Si se inició fuera del Hub sin gRPC autenticado, apágalo e inícialo desde el Hub."
    TransportIssue.AUTHENTICATION -> "Reconecta la pantalla. Si persiste, reinicia esta instancia desde el Hub para renovar su endpoint autenticado."
    TransportIssue.PROTOCOL_UNSUPPORTED -> "Comprueba la versión de Android Emulator; el protocolo se validó con 36.4.9."
    else -> "Espera y reintenta el diagnóstico. Puedes reconectar la pantalla o reiniciar únicamente esta instancia si el fallo persiste."
}
