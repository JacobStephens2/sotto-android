package page.stephens.sotto.engine.narrate

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.SottoDatabase
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
class NarrationCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, SottoDatabase::class.java)
        .allowMainThreadQueries().build()
    private val root = Files.createTempDirectory("narration-owner").toFile()
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val models = AtomicInteger()
    private val maximumModels = AtomicInteger()
    private val generations = AtomicInteger()
    private val maximumGenerations = AtomicInteger()
    private val failure = AtomicReference<Exception?>(null)
    private var callbackDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    private val coordinator by lazy { NarrationCoordinator(
        db, root,
        ManagedSynthesizerFactory {
            val count = models.incrementAndGet()
            maximumModels.accumulateAndGet(count, ::maxOf)
            object : ManagedSynthesizer {
                override val sampleRate = 24_000
                override fun synthesize(text: String, voiceId: String): FloatArray {
                    val active = generations.incrementAndGet()
                    maximumGenerations.accumulateAndGet(active, ::maxOf)
                    try {
                        failure.getAndSet(null)?.let { throw it }
                        if (text.startsWith("Held")) {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS)) { "test did not release chunk" }
                        }
                        return floatArrayOf(1f, 2f)
                    } finally {
                        generations.decrementAndGet()
                    }
                }
                override fun close() { check(generations.get() == 0); models.decrementAndGet() }
            }
        },
        encoderFactory = {
            object : PcmEncoder {
                override fun encode(samples: FloatArray) = byteArrayOf(1, 2, 3, 4)
            }
        },
        callbackDispatcher = callbackDispatcher,
    ) }

    @After
    fun close() = runBlocking {
        release.countDown()
        (callbackDispatcher as? PausedCallbacks)?.flush()
        withTimeout(10_000) { coordinator.shutdown().await() }
        db.close()
        root.deleteRecursively()
        Unit
    }

    @Test
    fun `stop retains the writer until its durable chunk and a new request waits`() = runBlocking {
        insert("a", "Held first. " + "More text. ".repeat(200))
        insert("b", "Next oration.", status = "STOPPED")
        val owner = coordinator.attach()
        owner.narrate("a").await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        owner.stop().await()
        assertEquals(NarrationCommandResult.Queued, owner.narrate("b").await())
        assertEquals("QUEUED", db.orations().byId("b")!!.status)
        assertEquals("NARRATING", db.orations().byId("a")!!.status)
        assertEquals(1, generations.get())
        release.countDown()
        awaitStatus("b", "COMPLETE")
        assertEquals("STOPPED", db.orations().byId("a")!!.status)
        assertEquals(1, db.orations().byId("a")!!.chunksDone)
        assertEquals(1, maximumModels.get())
        assertEquals(1, maximumGenerations.get())
        assertEquals(1, db.joblog().count("a", "stopped"))
    }

    @Test
    fun `busy retries retain durable progress and follow requests rather than creation time`() = runBlocking {
        insert("a", "Held first. " + "More text. ".repeat(200))
        insert("old", "Retry text.", status = "ERROR", created = 0)
        db.orations().saveProgress("old", 1, 4, 23, 1)
        File(root, "old/audio.aac").writeBytes(byteArrayOf(1, 2, 3, 4))
        insert("new", "New text.", created = 2)
        val owner = coordinator.attach()
        owner.narrate("a").await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertEquals(NarrationCommandResult.Queued, owner.narrate("old").await())
        val queued = db.orations().byId("old")!!
        assertEquals(1, queued.chunksDone)
        assertEquals(4L, queued.audioBytes)
        assertEquals(23L, queued.narratedMs)
        assertEquals(NarrationCommandResult.Coalesced, owner.narrate("old").await())
        assertEquals(queued.queuedAt, db.orations().byId("old")!!.queuedAt)
        owner.stop().await()
        release.countDown()
        awaitStatus("old", "COMPLETE")
        val order = db.joblog().recent().reversed().filter { it.event in listOf("started", "resumed") }
        assertEquals(listOf("a", "new", "old"), order.map { it.orationId })
    }

    @Test
    fun `same id requests coalesce without reviving a stopping run`() = runBlocking {
        insert("a", "Held first. " + "More text. ".repeat(200))
        val owner = coordinator.attach()
        owner.narrate("a").await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        owner.stop().await()
        repeat(3) { assertEquals(NarrationCommandResult.Coalesced, owner.narrate("a").await()) }
        release.countDown()
        awaitStatus("a", "STOPPED")
        assertEquals(1, db.orations().byId("a")!!.chunksDone)
        assertEquals(1, db.joblog().count("a", "started"))
        assertEquals(1, db.joblog().count("a", "stopped"))
        owner.narrate("a").await()
        awaitStatus("a", "COMPLETE")
        assertEquals(1, db.joblog().count("a", "resumed"))
        assertEquals(1, maximumGenerations.get())
    }

    @Test
    fun `delete fences admission and stops playback before waiting for the writer`() = runBlocking {
        insert("a", "Held first. " + "More text. ".repeat(200))
        val playbackStopped = CountDownLatch(1)
        val autoplay = AtomicInteger()
        val owner = coordinator.attach(NarrationCallbacks(
            onAudioReady = { autoplay.incrementAndGet() },
            onStopPlayback = { assertEquals("a", it); playbackStopped.countDown() },
        ))
        owner.narrate("a").await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val deletion = owner.delete("a")
        assertTrue(playbackStopped.await(5, TimeUnit.SECONDS))
        assertEquals(NarrationCommandResult.Deleting, owner.narrate("a").await())
        assertFalse(deletion.isCompleted)
        assertNotNull(db.orations().byId("a"))
        assertTrue(File(root, "a/audio.aac").exists())
        assertEquals(0, db.joblog().count("a", "deleted"))
        release.countDown()
        assertEquals(NarrationCommandResult.Deleted, withTimeout(5_000) { deletion.await() })
        assertNull(db.orations().byId("a"))
        assertFalse(File(root, "a").exists())
        assertEquals(1, db.joblog().count("a", "deleted"))
        assertEquals(0, autoplay.get())
    }

    @Test
    fun `detach returns while held and replacement waits for writer and model drain`() = runBlocking {
        insert("a", "Held first. " + "More text. ".repeat(200))
        insert("b", "Replacement.", status = "STOPPED")
        val lateAudio = AtomicInteger()
        val first = coordinator.attach(NarrationCallbacks(onAudioReady = { lateAudio.incrementAndGet() }))
        first.narrate("a").await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        first.narrate("b").await()
        first.detach()
        assertEquals(NarrationCommandResult.Detached, first.narrate("a").await())
        val replacement = coordinator.attach()
        replacement.narrate("b").await()
        assertEquals("NARRATING", db.orations().byId("a")!!.status)
        assertEquals("QUEUED", db.orations().byId("b")!!.status)
        assertEquals(1, models.get())
        release.countDown()
        awaitStatus("b", "COMPLETE")
        assertEquals("STOPPED", db.orations().byId("a")!!.status)
        assertEquals(0, db.joblog().count("a", "error"))
        assertEquals(0, lateAudio.get())
        assertEquals(1, maximumModels.get())
        assertEquals(1, maximumGenerations.get())
    }

    @Test
    fun `detach leaves queued work durable without unattached pickup`() = runBlocking {
        insert("a", "Held first. " + "More text. ".repeat(200))
        insert("b", "Pending.", status = "STOPPED")
        val owner = coordinator.attach()
        owner.narrate("a").await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        owner.narrate("b").await()
        owner.detach()
        release.countDown()
        withTimeout(5_000) { while (models.get() != 0) kotlinx.coroutines.yield() }
        assertEquals("QUEUED", db.orations().byId("b")!!.status)
        assertEquals(0, db.joblog().count("b", "started"))
    }

    @Test
    fun `invalid admissions never load a model`() = runBlocking {
        insert("complete", "Done.", status = "COMPLETE")
        insert("server", "Remote.", status = "STOPPED")
        db.orations().update(db.orations().byId("server")!!.copy(downloaded = false))
        insert("gate", "Diagnostic.", status = "NARRATING")
        val owner = coordinator.attach()
        assertEquals(NarrationCommandResult.Missing, owner.narrate("absent").await())
        assertEquals(NarrationCommandResult.Complete, owner.narrate("complete").await())
        assertEquals(NarrationCommandResult.ServerOnly, owner.narrate("server").await())
        assertEquals(NarrationCommandResult.DiagnosticOnly, owner.narrate("gate").await())
        assertEquals("STOPPED", db.orations().byId("gate")!!.status)
        assertEquals(0, maximumModels.get())
    }

    @Test
    fun `stop before dispatch prevents model load and source access`() = runBlocking {
        callbackDispatcher = PausedCallbacks()
        insert("a", "Text.", status = "STOPPED")
        File(root, "a/source.md").delete()
        val owner = coordinator.attach()
        owner.narrate("a").await()
        owner.stop().await()
        callbackDispatcher.flush()
        awaitStatus("a", "STOPPED")
        assertEquals(0, maximumModels.get())
        assertEquals(0, db.joblog().count("a", "error"))
    }

    @Test
    fun `missing source becomes error and pickup continues without loading its model`() = runBlocking {
        insert("a", "Lost source.")
        File(root, "a/source.md").delete()
        insert("b", "Next.", created = 2)
        coordinator.attach().narrate("a").await()
        awaitStatus("b", "COMPLETE")
        assertEquals("ERROR", db.orations().byId("a")!!.status)
        assertEquals(1, db.joblog().count("a", "error"))
        assertEquals(0, db.joblog().count("a", "started"))
    }

    @Test
    fun `storage full keeps durable checkpoint and cause while ordinary failure is error`() = runBlocking {
        insert("full", "One chunk.")
        failure.set(java.io.IOException("ENOSPC: No space left"))
        val owner = coordinator.attach()
        owner.narrate("full").await()
        awaitStatus("full", "STOPPED")
        assertEquals(NarrationCoordinator.STORAGE_FULL, db.orations().byId("full")!!.errorMessage)
        assertEquals(0L, db.orations().byId("full")!!.audioBytes)
        assertEquals(0L, File(root, "full/audio.aac").length())
        failure.set(IllegalStateException("ordinary failure"))
        insert("failed", "One chunk.", status = "STOPPED")
        owner.narrate("failed").await()
        awaitStatus("failed", "ERROR")
        assertEquals("ordinary failure", db.orations().byId("failed")!!.errorMessage)
        assertEquals(1, db.joblog().count("failed", "error"))
    }

    @Test
    fun `stop during final chunk completes and suppresses late autoplay`() = runBlocking {
        insert("a", "Held final.")
        val audio = AtomicInteger()
        val owner = coordinator.attach(NarrationCallbacks(onAudioReady = { audio.incrementAndGet() }))
        owner.narrate("a").await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        owner.stop().await()
        release.countDown()
        awaitStatus("a", "COMPLETE")
        assertEquals(4L, db.orations().byId("a")!!.audioBytes)
        assertEquals(1, db.joblog().count("a", "completed"))
        assertEquals(0, db.joblog().count("a", "stopped"))
        assertEquals(0, audio.get())
    }

    @Test
    fun `vanished dispatched row does not strand the next queued request`() = runBlocking {
        callbackDispatcher = PausedCallbacks()
        insert("a", "Vanishing.")
        insert("b", "Next.", created = 2)
        val owner = coordinator.attach()
        owner.narrate("a").await()
        db.orations().delete("a")
        callbackDispatcher.flush()
        withTimeout(5_000) {
            while (db.orations().byId("b")!!.status != "COMPLETE" || db.joblog().count("b", "completed") == 0) {
                callbackDispatcher.flush()
                kotlinx.coroutines.yield()
            }
        }
        assertEquals(0, db.joblog().count("a", "started"))
        assertEquals(1, db.joblog().count("b", "completed"))
    }

    @Test
    fun `audio readiness observes saved progress and stale callbacks are fenced on delete`() = runBlocking {
        callbackDispatcher = PausedCallbacks()
        insert("a", "Single.")
        val audio = AtomicInteger()
        val owner = coordinator.attach(NarrationCallbacks(onAudioReady = { audio.incrementAndGet() }))
        owner.narrate("a").await()
        callbackDispatcher.flush()
        awaitStatus("a", "COMPLETE")
        val deletion = owner.delete("a")
        owner.narrate("a").await() // Wait for the deletion barrier without running queued callbacks.
        callbackDispatcher.flush()
        assertEquals(NarrationCommandResult.Deleted, withTimeout(5_000) { deletion.await() })
        assertEquals(0, audio.get())
    }

    @Test
    fun `audio readiness is emitted only after saved durable bytes`() = runBlocking {
        insert("a", "Single.")
        val ready = CountDownLatch(1)
        coordinator.attach(NarrationCallbacks(onAudioReady = { row ->
            assertTrue(row.chunksDone > 0)
            assertEquals(row.audioBytes, db.orations().byId(row.id)!!.audioBytes)
            assertEquals(row.audioBytes, File(root, "${row.id}/audio.aac").length())
            ready.countDown()
        })).narrate("a").await()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `process startup retries orphan directory removal and records it`() = runBlocking {
        val orphan = File(root, "orphan").also { it.mkdirs() }
        File(orphan, "audio.aac").writeBytes(byteArrayOf(1))
        val owner = coordinator.attach()
        owner.narrate("missing").await()
        assertFalse(orphan.exists())
        assertEquals(1, db.joblog().count("orphan", "deleted"))
    }

    private fun insert(id: String, text: String, status: String = "QUEUED", created: Long = 1) {
        val dir = File(root, id).also { it.mkdirs() }
        File(dir, "source.md").writeText(text)
        db.orations().insert(OrationEntity(
            id = id, title = id, status = status, voiceId = "af_heart", words = 100,
            chunksTotal = null, createdAt = created, updatedAt = created,
        ))
    }

    private suspend fun awaitStatus(id: String, status: String) {
        val event = when (status) { "COMPLETE" -> "completed"; "STOPPED" -> "stopped"; else -> "error" }
        withTimeout(10_000) {
            while (db.orations().byId(id)?.status != status || db.joblog().count(id, event) == 0) {
                kotlinx.coroutines.yield()
            }
        }
    }

    private fun CoroutineDispatcher.flush() { (this as PausedCallbacks).flush() }

    private class PausedCallbacks : CoroutineDispatcher() {
        private val pending = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        fun flush() { while (true) (pending.poll() ?: break).run() }
    }
}
