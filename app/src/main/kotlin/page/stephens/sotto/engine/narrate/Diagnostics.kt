package page.stephens.sotto.engine.narrate

import kotlinx.coroutines.Deferred
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class ModelProfile(val threads: Int = 4, val measurement: Boolean = false)

sealed interface DiagnosticRequest {
    data class Gate(val bundledText: String) : DiagnosticRequest
    data class Measure(val text: String, val loops: Int = Int.MAX_VALUE, val minutes: Int = 0) : DiagnosticRequest
    data class Threads(val count: Int) : DiagnosticRequest
    data object Stop : DiagnosticRequest
    data object ResetGate : DiagnosticRequest
}

sealed interface DiagnosticResult {
    data object Accepted : DiagnosticResult
    data object Busy : DiagnosticResult
    data object Detached : DiagnosticResult
    data object StopRequested : DiagnosticResult
    data object Configured : DiagnosticResult
    data object Reset : DiagnosticResult
    data object Superseded : DiagnosticResult
    data class Failed(val message: String) : DiagnosticResult
}

sealed interface DiagnosticEvent {
    data class Status(val text: String) : DiagnosticEvent
    data class Line(val text: String) : DiagnosticEvent
    data class Audio(val samples: FloatArray, val sampleRate: Int) : DiagnosticEvent
    data class Measured(val measurement: Measurement) : DiagnosticEvent
}

interface DiagnosticEnvironment {
    fun elapsedMs(): Long
    fun uptimeMs(): Long
    fun batteryPct(): Int
    fun thermal(): String
    fun pssKb(): Long
}

data class Measurement(
    val chunks: Int,
    val audioSeconds: Double,
    val generateMs: Long,
    val wallSeconds: Double,
    val suspendedSeconds: Double,
    val batteryStart: Int,
    val batteryEnd: Int,
    val thermal: String,
    val peakPssKb: Long,
    val threads: Int,
    val reason: String,
) {
    val generateRtf: Double get() = audioSeconds / (generateMs / 1000.0)
    val wallRtf: Double get() = audioSeconds / wallSeconds
    val batteryPerHour: Double get() = (batteryStart - batteryEnd) / (wallSeconds / 3600.0)

    fun summary(): String = """
        |== SPIKE-002 SUMMARY ($reason) ==
        |chunks=$chunks  audio=%.0fs  synth=%.0fs  wall=%.0fs  suspended=%.0fs
        |RTF (synth basis) = %.2fx   RTF (wall basis) = %.2fx
        |battery $batteryStart%% -> $batteryEnd%% (Δ${batteryStart - batteryEnd} over %.1f min => %.1f%%/hr)
        |thermal-final=$thermal  peakPSS=${peakPssKb / 1024}MB
        |threads=$threads sid=0 abi=arm64-v8a
    """.trimMargin().format(audioSeconds, generateMs / 1000.0, wallSeconds, suspendedSeconds,
        generateRtf, wallRtf, wallSeconds / 60.0, batteryPerHour)
}

class AttachedDiagnostics internal constructor(
    private val coordinator: NarrationCoordinator,
    internal val callback: AtomicReference<((DiagnosticEvent) -> Unit)?>,
) {
    internal val valid = AtomicBoolean(true)
    internal val latest = AtomicReference<NarrationCoordinator.DiagnosticRun?>(null)
    internal var threads = 4

    fun request(request: DiagnosticRequest): Deferred<DiagnosticResult> = coordinator.submitDiagnostic(this, request)
    fun detach() = coordinator.detachDiagnostics(this)

    internal fun invalidate() = synchronized(this) {
        valid.set(false)
        callback.set(null)
        latest.get()?.stop?.set(true)
    }
}
