package page.stephens.sotto.engine.narrate

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.SottoDatabase
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class DiagnosticControlSpecRegressionTest {
    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val fixture = Fixture()
        try { block(fixture) } finally {
            fixture.release.countDown()
            withTimeout(10_000) { fixture.worker.shutdown().await() }
            fixture.db.close()
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun `explicit Stop preserves the completed measurement summary`() = fixture { fixture ->
        val events = ConcurrentLinkedQueue<DiagnosticEvent>()
        val lab = fixture.worker.attachDiagnostics { events.add(it) }
        assertEquals(DiagnosticResult.Accepted,
            lab.request(DiagnosticRequest.Measure("Held measurement.", loops = 100)).await())
        assertTrue(fixture.entered.await(5, TimeUnit.SECONDS))
        assertEquals(DiagnosticResult.StopRequested, lab.request(DiagnosticRequest.Stop).await())
        fixture.release.countDown()
        assertTrue(fixture.closed.await(5, TimeUnit.SECONDS))
        val summary = events.filterIsInstance<DiagnosticEvent.Measured>().singleOrNull()
        assertNotNull("An explicit Stop must retain the completed measurement summary", summary)
        assertEquals(1, summary!!.measurement.chunks)
        assertEquals("stopped/superseded", summary.measurement.reason)
        assertTrue(events.filterIsInstance<DiagnosticEvent.Line>().any { it.text.contains("SPIKE-002 SUMMARY") })
    }

    @Test
    fun `thread change drains diagnostics with ordinary work waiting`() = fixture { fixture ->
        val lab = fixture.worker.attachDiagnostics()
        assertEquals(DiagnosticResult.Accepted,
            lab.request(DiagnosticRequest.Gate("Held gate. " + "More. ".repeat(400))).await())
        assertTrue(fixture.entered.await(5, TimeUnit.SECONDS))
        fixture.db.orations().insert(OrationEntity(
            id = "ordinary", title = "Ordinary", status = "STOPPED", voiceId = "af_heart",
            words = 1, chunksTotal = null, createdAt = 1, updatedAt = 1))
        val ordinary = java.io.File(fixture.root, "ordinary").also { it.mkdirs() }
        java.io.File(ordinary, "source.md").writeText("Ordinary.")
        val narrator = fixture.worker.attach()
        assertEquals(NarrationCommandResult.Queued, narrator.narrate("ordinary").await())
        val reload = lab.request(DiagnosticRequest.Threads(2))
        narrator.narrate("missing").await()
        assertFalse("Thread changes must stop and drain the diagnostic even with waiting ordinary work", reload.isCompleted)
        fixture.release.countDown()
        assertEquals(DiagnosticResult.Configured, withTimeout(5_000) { reload.await() })
        assertEquals("STOPPED", fixture.db.orations().byId("gate")!!.status)
    }

    private class Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, SottoDatabase::class.java).allowMainThreadQueries().build()
        val root = Files.createTempDirectory("diagnostic-controls-spec").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val worker = NarrationCoordinator(
            db, root,
            ManagedSynthesizerFactory {
                object : ManagedSynthesizer {
                    override val sampleRate = 24_000
                    override fun synthesize(text: String, voiceId: String): FloatArray {
                        if (text.startsWith("Held")) {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                        return FloatArray(2_400)
                    }
                    override fun close() { closed.countDown() }
                }
            },
            encoderFactory = { object : PcmEncoder {
                override fun encode(samples: FloatArray) = byteArrayOf(1, 2, 3, 4)
            } },
            callbackDispatcher = Dispatchers.Unconfined,
        )
    }
}
