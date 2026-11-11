package dev.picon.androidhub.core

enum class DiagnosticStage { PROCESS, ADB, ENDPOINT, GRPC, FIRST_FRAME }
enum class DiagnosticOutcome { PASS, FAIL, SKIPPED }
enum class TransportIssue {
    OFFLINE, PROCESS_EXITED, INSTANCE_CHANGED, ADB_MISSING, ADB_UNAVAILABLE, ENDPOINT_MISSING,
    AUTHENTICATION, UNREACHABLE, PROTOCOL_UNSUPPORTED, RPC_TIMEOUT, FRAME_TIMEOUT, FRAME_MISSING, UNKNOWN,
}

data class DiagnosticCheck(val stage: DiagnosticStage, val outcome: DiagnosticOutcome, val detail: String, val durationMs: Long = 0)
data class TransportReport(val checks: List<DiagnosticCheck>, val issue: TransportIssue? = null, val suggestion: String) {
    val healthy: Boolean get() = issue == null && checks.all { it.outcome == DiagnosticOutcome.PASS }
}

interface TransportDiagnostics {
    suspend fun inspect(device: AvailableEmulator, onCheck: (DiagnosticCheck) -> Unit): TransportReport
}
