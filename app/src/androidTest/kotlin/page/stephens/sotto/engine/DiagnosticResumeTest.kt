package page.stephens.sotto.engine

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import page.stephens.sotto.engine.narrate.DiagnosticRequest
import page.stephens.sotto.engine.narrate.DiagnosticResult
import page.stephens.sotto.engine.narrate.ManagedSynthesizer
import page.stephens.sotto.engine.narrate.ManagedSynthesizerFactory
import page.stephens.sotto.engine.narrate.NarrationCoordinator
import page.stephens.sotto.engine.store.SottoDatabase
import page.stephens.sotto.shell.synth.AdtsAacEncoder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DiagnosticResumeTest {
    @Test
    fun gateResumesByteIdenticallyThroughRoomAndAdtsAfterRecovery() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, SottoDatabase::class.java).build()
        val root = File(context.cacheDir, "diagnostic-resume").also { it.deleteRecursively(); it.mkdirs() }
        val hold = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val live = AtomicInteger()
        val maximum = AtomicInteger()
        val generating = AtomicBoolean(false)
        val factory = ManagedSynthesizerFactory {
            maximum.accumulateAndGet(live.incrementAndGet(), ::maxOf)
            object : ManagedSynthesizer {
                override val sampleRate = 24_000
                override fun synthesize(text: String, voiceId: String): FloatArray {
                    check(generating.compareAndSet(false, true))
                    try {
                        if (hold.compareAndSet(true, false)) {
                            entered.countDown()
                            check(release.await(15, TimeUnit.SECONDS))
                        }
                        return FloatArray(2400) { index -> ((text.hashCode() * 17 + index) % 997) / 997f }
                    } finally { generating.set(false) }
                }
                override fun close() { check(!generating.get()); live.decrementAndGet() }
            }
        }
        fun coordinator() = NarrationCoordinator(db, root, factory, ::AdtsAacEncoder)
        suspend fun awaitStatus(status: String) = withTimeout(15_000) {
            val event = if (status == "COMPLETE") "completed" else "stopped"
            while (db.orations().byId("gate")?.status != status || db.joblog().count("gate", event) == 0) delay(5)
        }
        var worker = coordinator()
        val text = (1..70).joinToString(" ") { "Sentence $it carries a steady number of words along." }
        try {
            var lab = worker.attachDiagnostics()
            assertEquals(DiagnosticResult.Accepted, lab.request(DiagnosticRequest.Gate(text)).await())
            awaitStatus("COMPLETE")
            val reference = File(root, "gate/audio.aac").readBytes()
            assertEquals(DiagnosticResult.Reset, lab.request(DiagnosticRequest.ResetGate).await())
            hold.set(true)
            lab.request(DiagnosticRequest.Gate(text)).await()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            lab.request(DiagnosticRequest.Stop).await()
            release.countDown()
            awaitStatus("STOPPED")
            assertEquals(1, db.orations().byId("gate")!!.chunksDone)
            withTimeout(15_000) { worker.shutdown().await() }
            val checkpoint = db.orations().byId("gate")!!
            db.orations().setStatus("gate", "NARRATING", 1)
            File(root, "gate/audio.aac").appendBytes(byteArrayOf(1, 2, 3))
            File(root, "gate/source.md").delete()
            worker = coordinator()
            worker.attach().narrate("missing").await()
            assertEquals("STOPPED", db.orations().byId("gate")!!.status)
            assertEquals(checkpoint.audioBytes, db.orations().byId("gate")!!.audioBytes)
            lab = worker.attachDiagnostics()
            lab.request(DiagnosticRequest.Gate(text)).await()
            awaitStatus("COMPLETE")
            assertArrayEquals(reference, File(root, "gate/audio.aac").readBytes())
            assertEquals(text, File(root, "gate/source.md").readText())
            assertEquals(1, maximum.get())
            assertEquals(1, db.joblog().count("gate", "resumed"))
        } finally {
            release.countDown()
            withTimeout(15_000) { worker.shutdown().await() }
            db.close()
            root.deleteRecursively()
        }
    }
}
