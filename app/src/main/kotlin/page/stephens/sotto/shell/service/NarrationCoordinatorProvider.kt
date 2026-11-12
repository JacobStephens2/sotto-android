package page.stephens.sotto.shell.service

import android.content.Context
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
                ManagedSynthesizerFactory { SherpaSynthesizer(app, numThreads = 4) },
                encoderFactory = ::AdtsAacEncoder,
            ).also { instance = it }
        }
    }

    // Robolectric replaces the Application between tests in one JVM process.
    internal fun shutdownForTest() = synchronized(this) {
        instance?.shutdown().also { instance = null }
    }
}
