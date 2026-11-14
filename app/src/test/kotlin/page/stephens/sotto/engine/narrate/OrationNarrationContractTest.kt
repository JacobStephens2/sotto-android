package page.stephens.sotto.engine.narrate

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import page.stephens.sotto.engine.store.DurableSourcePublisher
import page.stephens.sotto.engine.store.OrationCreation
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.SourcePublisher
import page.stephens.sotto.engine.store.SottoDatabase
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class OrationNarrationContractTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), SottoDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val root = Files.createTempDirectory("oration-contract").toFile()
    private val holdLoad = AtomicBoolean()
    private val loadEntered = CountDownLatch(1)
    private val releaseLoad = CountDownLatch(1)
    private val holdChunk = AtomicBoolean()
    private val chunkEntered = CountDownLatch(1)
    private val releaseChunk = CountDownLatch(1)
    private val holdClose = AtomicBoolean()
    private val closeEntered = CountDownLatch(1)
    private val releaseClose = CountDownLatch(1)
    private val modelFailure = AtomicReference<Exception?>(null)
    private val encoderFailure = AtomicReference<Exception?>(null)
    private var encodingFailure: Exception = IllegalStateException("encoding failed")
    private var failEncodingAt = 0
    private var failNarrationAt = 0
    private val constructed = AtomicInteger()
    private val encoded = AtomicInteger()
    private val narrated = AtomicInteger()
    private val events = ConcurrentLinkedQueue<DiagnosticEvent>()
    private var publisher: SourcePublisher = DurableSourcePublisher
    private val worker by lazy {
        NarrationCoordinator(db, root,
            ManagedSynthesizerFactory {
                constructed.incrementAndGet()
                if (holdLoad.compareAndSet(true, false)) {
                    loadEntered.countDown()
                    check(releaseLoad.await(10, TimeUnit.SECONDS))
                }
                modelFailure.getAndSet(null)?.let { throw it }
                object : ManagedSynthesizer {
                    override val sampleRate = 24_000
                    override fun synthesize(text: String, voiceId: String): FloatArray {
                        if (holdChunk.compareAndSet(true, false)) {
                            chunkEntered.countDown()
                            check(releaseChunk.await(10, TimeUnit.SECONDS))
                        }
                        if (narrated.incrementAndGet() == failNarrationAt) error("narration failed")
                        return floatArrayOf(1f, 2f)
                    }
                    override fun close() {
                        if (holdClose.get()) {
                            closeEntered.countDown()
                            check(releaseClose.await(10, TimeUnit.SECONDS))
                        }
                    }
                }
            },
            encoderFactory = {
                encoderFailure.getAndSet(null)?.let { throw it }
                object : PcmEncoder {
                    override fun encode(samples: FloatArray): ByteArray {
                        if (encoded.incrementAndGet() == failEncodingAt) throw encodingFailure
                        return byteArrayOf(1, 2, 3, 4)
                    }
                }
            },
            callbackDispatcher = Dispatchers.Unconfined,
            clock = { 100L },
            creation = OrationCreation(db, root, publisher, clock = { 100L }),
        )
    }

    @After
    fun close() = runBlocking {
        releaseLoad.countDown()
        releaseChunk.countDown()
        releaseClose.countDown()
        withTimeout(10_000) { worker.shutdown().await() }
        db.close()
        root.deleteRecursively()
        Unit
    }

    @Test
    fun `ordinary model construction failure records one error and picks up next`() = setupFailure(gate = false, model = true)

    @Test
    fun `gate model construction failure is diagnostic only and picks up next`() = setupFailure(gate = true, model = true)

    @Test
    fun `ordinary encoder construction failure records one error and picks up next`() = setupFailure(gate = false, model = false)

    @Test
    fun `gate encoder construction failure records one error and picks up next`() = setupFailure(gate = true, model = false)

    private fun setupFailure(gate: Boolean, model: Boolean) = runBlocking {
        holdLoad.set(true)
        val message = if (model) "model failed" else "encoder failed"
        (if (model) modelFailure else encoderFailure).set(IllegalStateException(message))
        val owner = worker.attach()
        assertEquals(NarrationCommandResult.Missing, owner.narrate("absent").await())
        val id = if (gate) "gate" else "ordinary"
        if (gate) worker.attachDiagnostics(events::add).request(DiagnosticRequest.Gate("A document.")).await()
        else { insert(id); owner.narrate(id).await() }
        assertTrue(loadEntered.await(5, TimeUnit.SECONDS))
        insert("next")
        owner.narrate("next").await()
        releaseLoad.countDown()
        awaitTerminal("next", "COMPLETE")
        if (gate && model) {
            assertNull(db.orations().byId(id))
            assertFalse(File(root, id).exists())
            assertEquals(0, db.joblog().count(id, "error"))
        } else {
            val row = db.orations().byId(id)!!
            assertEquals("ERROR", row.status)
            assertEquals(message, row.errorMessage)
            assertEquals(0L, row.audioBytes)
            assertEquals(1, db.joblog().count(id, "error"))
            assertEquals(100L, db.joblog().recent().single { it.orationId == id && it.event == "error" }.at)
            assertEquals(0L, File(root, "$id/audio.aac").length())
        }
        if (gate) assertTrue(events.filterIsInstance<DiagnosticEvent.Line>().any { it.text.contains("ERROR:") && it.text.contains(message) })
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(root, "next/audio.aac").readBytes())
    }

    @Test
    fun `ordinary encoding failure retains saved bytes and continues pickup`() = chunkFailure(gate = false, storageFull = false)

    @Test
    fun `gate encoding failure retains saved bytes and reports diagnostic error`() = chunkFailure(gate = true, storageFull = false)

    @Test
    fun `ordinary storage full retains saved bytes and stopped cause`() = chunkFailure(gate = false, storageFull = true)

    @Test
    fun `gate storage full retains saved bytes and stopped verdict`() = chunkFailure(gate = true, storageFull = true)

    private fun chunkFailure(gate: Boolean, storageFull: Boolean) = runBlocking {
        holdChunk.set(true)
        failEncodingAt = 2
        if (storageFull) encodingFailure = IOException("ENOSPC: No space left")
        val owner = worker.attach()
        assertEquals(NarrationCommandResult.Missing, owner.narrate("absent").await())
        val id = if (gate) "gate" else "ordinary"
        val text = "Many sentences. ".repeat(200)
        if (gate) worker.attachDiagnostics(events::add).request(DiagnosticRequest.Gate(text)).await()
        else { insert(id, text); owner.narrate(id).await() }
        assertTrue(chunkEntered.await(5, TimeUnit.SECONDS))
        insert("next")
        owner.narrate("next").await()
        releaseChunk.countDown()
        awaitTerminal("next", "COMPLETE")
        val row = db.orations().byId(id)!!
        assertEquals(if (storageFull) "STOPPED" else "ERROR", row.status)
        assertEquals(if (storageFull) NarrationCoordinator.STORAGE_FULL else "encoding failed", row.errorMessage)
        assertEquals(1, row.chunksDone)
        assertEquals(4L, row.audioBytes)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(root, "$id/audio.aac").readBytes())
        assertEquals(1, db.joblog().count(id, "chunk-done"))
        assertEquals(1, terminalLogs(id).size)
        if (gate) assertTrue(events.filterIsInstance<DiagnosticEvent.Line>().any {
            if (storageFull) it.text.startsWith("gate stopped: storage full at 1/")
            else it.text.contains("ERROR:") && it.text.contains("encoding failed")
        })
    }

    @Test
    fun `gate narration failure records one error and continues pickup`() = runBlocking {
        holdChunk.set(true)
        failNarrationAt = 2
        val owner = worker.attach()
        worker.attachDiagnostics(events::add).request(DiagnosticRequest.Gate("Many sentences. ".repeat(200))).await()
        assertTrue(chunkEntered.await(5, TimeUnit.SECONDS))
        insert("next")
        owner.narrate("next").await()
        releaseChunk.countDown()
        awaitTerminal("next", "COMPLETE")
        val row = db.orations().byId("gate")!!
        assertEquals("ERROR", row.status)
        assertEquals("narration failed", row.errorMessage)
        assertEquals(1, row.chunksDone)
        assertEquals(4L, row.audioBytes)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(root, "gate/audio.aac").readBytes())
        assertEquals(1, terminalLogs("gate").size)
        assertTrue(events.filterIsInstance<DiagnosticEvent.Line>().any { it.text.contains("ERROR:") && it.text.contains("narration failed") })
    }

    @Test
    fun `gate preparation failure leaves a legacy checkpoint intact and pickup continues`() = runBlocking {
        publisher = SourcePublisher { _, _, _ -> throw IOException("source failed") }
        insert("gate", "Saved.")
        File(root, "gate/source.md").delete()
        db.orations().saveProgress("gate", 1, 4, 23, 1)
        File(root, "gate/audio.aac").writeBytes(byteArrayOf(1, 2, 3, 4))
        val before = db.orations().byId("gate")!!
        val error = CountDownLatch(1)
        worker.attachDiagnostics {
            if (it is DiagnosticEvent.Line && it.text.contains("ERROR:") && it.text.contains("source failed")) error.countDown()
        }.request(DiagnosticRequest.Gate("Bundled.")).await()
        assertTrue(error.await(5, TimeUnit.SECONDS))
        assertEquals(before, db.orations().byId("gate"))
        assertTrue(terminalLogs("gate").isEmpty())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(root, "gate/audio.aac").readBytes())
        assertFalse(File(root, "gate/source.md").exists())
        insert("next")
        worker.attach().narrate("next").await()
        awaitTerminal("next", "COMPLETE")
    }

    @Test
    fun `gate Stop during model loading leaves source and row unprepared`() = runBlocking {
        holdLoad.set(true)
        val lab = worker.attachDiagnostics(events::add)
        lab.request(DiagnosticRequest.Gate("Bundled.")).await()
        assertTrue(loadEntered.await(5, TimeUnit.SECONDS))
        assertEquals(DiagnosticResult.StopRequested, lab.request(DiagnosticRequest.Stop).await())
        releaseLoad.countDown()
        withTimeout(5_000) { worker.shutdown().await() }
        assertNull(db.orations().byId("gate"))
        assertFalse(File(root, "gate").exists())
        assertTrue(events.none { it is DiagnosticEvent.Status })
    }

    @Test
    fun `gate Stop during final chunk keeps completion and terminal verdict`() = runBlocking {
        holdChunk.set(true)
        val lab = worker.attachDiagnostics(events::add)
        lab.request(DiagnosticRequest.Gate("One chunk.")).await()
        assertTrue(chunkEntered.await(5, TimeUnit.SECONDS))
        lab.request(DiagnosticRequest.Stop).await()
        releaseChunk.countDown()
        awaitTerminal("gate", "COMPLETE")
        withTimeout(5_000) { while (events.none { it is DiagnosticEvent.Line && it.text.startsWith("GATE COMPLETE:") }) yield() }
        assertEquals(1, terminalLogs("gate").size)
        assertEquals(4L, db.orations().byId("gate")!!.audioBytes)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(root, "gate/audio.aac").readBytes())
    }

    @Test
    fun `Stop during a previous model close prevents replacement construction`() = stopDuringClose(detach = false)

    @Test
    fun `detach during a previous model close prevents replacement construction`() = stopDuringClose(detach = true)

    private fun stopDuringClose(detach: Boolean) = runBlocking {
        insert("first")
        insert("barrier")
        val nextOwner = AtomicReference<AttachedNarrator>()
        val first = worker.attach(NarrationCallbacks(onStopPlayback = {
            // Publish waiting work and replace the owner while deletion blocks pickup.
            insert("replacement", status = "QUEUED")
            holdClose.set(true)
            nextOwner.set(worker.attach())
        }))
        first.narrate("first").await()
        awaitTerminal("first", "COMPLETE")
        withTimeout(5_000) { while (first.facts.value.activeId != null) yield() }
        assertEquals(NarrationCommandResult.Deleted, first.delete("barrier").await())
        val replacement = nextOwner.get()
        assertTrue(closeEntered.await(5, TimeUnit.SECONDS))
        assertEquals("NARRATING", db.orations().byId("replacement")!!.status)
        if (detach) replacement.detach()
        else assertEquals(NarrationCommandResult.StopRequested, replacement.stop().await())
        releaseClose.countDown()
        awaitTerminal("replacement", "STOPPED")
        assertEquals(1, constructed.get())
        assertEquals(1, narrated.get())
        assertFalse(File(root, "replacement/audio.aac").exists())
        assertEquals("before model load", terminalLogs("replacement").single().detail)
        assertEquals(100L, terminalLogs("replacement").single().at)
    }

    private fun insert(id: String, text: String = "One chunk.", status: String = "STOPPED") {
        File(root, id).mkdirs()
        File(root, "$id/source.md").writeText(text)
        db.orations().insert(OrationEntity(id, id, status, "af_heart", 100, null, createdAt = 1, updatedAt = 1))
    }

    private fun terminalLogs(id: String) = db.joblog().recent().filter {
        it.orationId == id && it.event in setOf("completed", "stopped", "error")
    }

    private suspend fun awaitTerminal(id: String, status: String) = withTimeout(10_000) {
        while (db.orations().byId(id)?.status != status || terminalLogs(id).isEmpty()) yield()
    }
}
