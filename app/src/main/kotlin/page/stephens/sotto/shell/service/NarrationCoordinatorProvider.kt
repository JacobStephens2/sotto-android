package page.stephens.sotto.shell.service

import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import page.stephens.sotto.engine.narrate.DiagnosticEnvironment
import page.stephens.sotto.engine.narrate.ManagedSynthesizerFactory
import page.stephens.sotto.engine.narrate.NarrationCoordinator
import page.stephens.sotto.engine.store.SottoDatabase
import page.stephens.sotto.shell.synth.AdtsAacEncoder
import page.stephens.sotto.shell.synth.SherpaSynthesizer
import java.io.File

internal object NarrationCoordinatorProvider {
    @Volatile private var instance: NarrationCoordinator? = null

    fun get(context: Context): NarrationCoordinator = instance ?: synchronized(this) {
        instance ?: context.applicationContext.let { app ->
            NarrationCoordinator(
                SottoDatabase.open(app), File(app.filesDir, "orations"),
                ManagedSynthesizerFactory { profile ->
                    SherpaSynthesizer(app, numThreads = profile.threads,
                        sidOf = if (profile.measurement) { _ -> 0 } else SherpaSynthesizer.DEFAULT_SIDS)
                },
                encoderFactory = ::AdtsAacEncoder,
                diagnosticEnvironment = object : DiagnosticEnvironment {
                    override fun elapsedMs() = SystemClock.elapsedRealtime()
                    override fun uptimeMs() = SystemClock.uptimeMillis()
                    override fun batteryPct() = (app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    override fun thermal(): String {
                        val status = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
                        return arrayOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")
                            .getOrElse(status) { "?$status" }
                    }
                    override fun pssKb(): Long = Debug.MemoryInfo().let {
                        Debug.getMemoryInfo(it)
                        it.totalPss.toLong()
                    }
                },
            ).also { instance = it }
        }
    }

    // Robolectric replaces the Application between tests in one JVM process.
    internal fun shutdownForTest() = synchronized(this) {
        instance?.shutdown().also { instance = null }
    }
}
