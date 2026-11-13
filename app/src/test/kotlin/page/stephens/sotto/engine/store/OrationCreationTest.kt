package page.stephens.sotto.engine.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import page.stephens.sotto.engine.narrate.ManagedSynthesizer
import page.stephens.sotto.engine.narrate.ManagedSynthesizerFactory
import page.stephens.sotto.engine.narrate.NarrationCoordinator
import page.stephens.sotto.engine.narrate.PcmEncoder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class OrationCreationTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), SottoDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val root = Files.createTempDirectory("oration-creation").toFile()

    @After
    fun close() {
        db.close()
        root.deleteRecursively()
    }

    @Test
    fun `ordinary creation publishes source before its queued row and created log`() {
        val creation = OrationCreation(db, root, SourcePublisher { pending, source, text ->
            assertTrue(db.orations().all().isEmpty())
            assertTrue(db.joblog().recent().isEmpty())
            DurableSourcePublisher.publish(pending, source, text)
            assertTrue(pending.exists().not())
            assertEquals("# Hello\n\nA saved document.", source.readText())
            assertTrue(db.orations().all().isEmpty())
        }, nextId = { "ordinary" }, clock = { 42L })

        assertEquals(CreationResult.Created("ordinary"),
            creation.create("  # Hello\n\nA saved document.  ", "Chosen title", "bf_emma"))
        val row = db.orations().byId("ordinary")!!
        assertEquals("Chosen title", row.title)
        assertEquals("bf_emma", row.voiceId)
        assertEquals(5, row.words)
        assertEquals("QUEUED", row.status)
        assertEquals(42L, row.queuedAt)
        assertEquals(1, db.joblog().count("ordinary", "created"))
        assertEquals("voice=bf_emma", db.joblog().recent().single().detail)
        creation.sweepOrphans()
        assertEquals("# Hello\n\nA saved document.", File(root, "ordinary/source.md").readText())
    }

    @Test
    fun `cleanup waits for live publication and the committed oration remains narratable`() = runBlocking {
        val published = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sweeping = CountDownLatch(1)
        val swept = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val creation = OrationCreation(db, root, SourcePublisher { pending, source, text ->
            DurableSourcePublisher.publish(pending, source, text)
            published.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }, nextId = { "live" })
        try {
            val saving = pool.submit<CreationResult> { creation.create("A live document.", "Live", "af_heart") }
            assertTrue(published.await(5, TimeUnit.SECONDS))
            assertTrue(db.orations().all().isEmpty())
            val cleanup = pool.submit {
                sweeping.countDown()
                creation.sweepOrphans()
                swept.countDown()
            }
            assertTrue(sweeping.await(5, TimeUnit.SECONDS))
            assertFalse("cleanup must wait while the source has no row", swept.await(200, TimeUnit.MILLISECONDS))
            release.countDown()
            assertEquals(CreationResult.Created("live"), saving.get(5, TimeUnit.SECONDS))
            cleanup.get(5, TimeUnit.SECONDS)
            assertEquals("A live document.", File(root, "live/source.md").readText())
            assertEquals("QUEUED", db.orations().all().single().status)
            assertEquals(1, db.joblog().count("live", "created"))

            val worker = NarrationCoordinator(db, root, ManagedSynthesizerFactory {
                object : ManagedSynthesizer {
                    override val sampleRate = 24_000
                    override fun synthesize(text: String, voiceId: String) = floatArrayOf(1f)
                    override fun close() {}
                }
            }, { object : PcmEncoder {
                override fun encode(samples: FloatArray) = byteArrayOf(1, 2, 3)
            } }, callbackDispatcher = Dispatchers.Unconfined, creation = creation)
            try {
                worker.attach().narrate("live").await()
                withTimeout(5_000) {
                    while (db.joblog().count("live", "completed") == 0) kotlinx.coroutines.yield()
                }
                assertEquals("COMPLETE", db.orations().byId("live")!!.status)
                assertArrayEquals(byteArrayOf(1, 2, 3), File(root, "live/audio.aac").readBytes())
            } finally {
                withTimeout(5_000) { worker.shutdown().await() }
            }
        } finally {
            release.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `invalid ordinary input leaves no preparation or metadata`() {
        val creation = OrationCreation(db, root)
        assertEquals(CreationResult.Empty, creation.create(" \n ", "Empty", "af_heart"))
        val oversized = "a".repeat(409601)
        assertEquals(CreationResult.TooLarge(409601), creation.create(oversized, "Large", "af_heart"))
        assertTrue(root.listFiles()!!.isEmpty())
        assertTrue(db.orations().all().isEmpty())
        assertTrue(db.joblog().recent().isEmpty())
    }

    @Test
    fun `source faults before and after rename roll back only the new preparation`() {
        val retained = seedGate()
        for (afterRename in listOf(false, true)) {
            val creation = OrationCreation(db, root, SourcePublisher { pending, source, text ->
                if (afterRename) DurableSourcePublisher.publish(pending, source, text)
                else pending.writeText("partial source")
                throw IOException("storage failed")
            }, nextId = { "failed" })
            assertTrue(creation.create("New source", "New", "af_heart") is CreationResult.StorageFailure)
            assertNull(db.orations().byId("failed"))
            assertEquals(0, db.joblog().count("failed", "created"))
            assertFalse(File(root, "failed").exists())
            assertEquals(retained, db.orations().byId("gate"))
            assertEquals("Saved source.", File(root, "gate/source.md").readText())
            assertArrayEquals(byteArrayOf(9, 8), File(root, "gate/audio.aac").readBytes())
            assertEquals(1, db.joblog().count("gate", "created"))
        }
    }

    @Test
    fun `a created log database failure rolls back the inserted row and source`() {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_created BEFORE INSERT ON joblog BEGIN SELECT RAISE(ABORT, 'log unavailable'); END",
        )
        val creation = OrationCreation(db, root, DurableSourcePublisher, nextId = { "failed" })
        assertTrue(creation.create("Saved before transaction.", "Failed", "af_heart") is CreationResult.StorageFailure)
        assertTrue(db.orations().all().isEmpty())
        assertTrue(db.joblog().recent().isEmpty())
        assertFalse(File(root, "failed").exists())
    }

    @Test
    fun `ordinary ID collisions and directory failures preserve existing artifacts`() {
        val row = seedGate()
        val rowCollision = OrationCreation(db, root, DurableSourcePublisher, nextId = { "gate" })
        assertTrue(rowCollision.create("Replacement", "Wrong", "am_adam") is CreationResult.StorageFailure)
        val orphan = File(root, "orphan").also { it.mkdir() }
        File(orphan, "source.md").writeText("Previous attempt")
        val directoryCollision = OrationCreation(db, root, DurableSourcePublisher, nextId = { "orphan" })
        assertTrue(directoryCollision.create("Replacement", "Wrong", "am_adam") is CreationResult.StorageFailure)
        assertEquals("Previous attempt", File(orphan, "source.md").readText())
        assertEquals(row, db.orations().byId("gate"))
        assertEquals("Saved source.", File(root, "gate/source.md").readText())

        val unavailable = File(root, "file-instead-of-directory").also { it.writeText("retain me") }
        assertTrue(OrationCreation(db, unavailable).create("Text", "Name", "af_heart") is CreationResult.StorageFailure)
        assertEquals("retain me", unavailable.readText())
        assertEquals(1, db.joblog().recent().size)
    }

    @Test
    fun `gate preparation shares publication and saved source wins over a changed bundle`() {
        var publications = 0
        val creation = OrationCreation(db, root, SourcePublisher { pending, source, text ->
            publications++
            assertNull(db.orations().byId("gate"))
            DurableSourcePublisher.publish(pending, source, text)
        }, clock = { 42L })
        val first = creation.prepareGate("# Heading\n\nSaved source.") as GatePreparation.Ready
        assertEquals("Heading.\n\nSaved source.", first.cleanedSource)
        assertEquals(3, first.row.words)
        assertEquals("Phase 1 gate", first.row.title)
        assertEquals("af_heart", first.row.voiceId)
        assertEquals(first, creation.prepareGate("Changed bundle."))
        assertEquals(1, publications)
        assertEquals(1, db.joblog().count("gate", "created"))
    }

    @Test
    fun `legacy gate repair retains its checkpoint and rolls back only its own source on failure`() {
        val row = seedGate()
        File(root, "gate/source.md").delete()
        val logs = db.joblog().recent()
        val failing = OrationCreation(db, root, SourcePublisher { pending, source, text ->
            DurableSourcePublisher.publish(pending, source, text)
            throw IOException("failed after rename")
        })
        assertTrue(failing.prepareGate("Legacy bundle.") is GatePreparation.StorageFailure)
        assertFalse(File(root, "gate/source.md").exists())
        assertEquals(row, db.orations().byId("gate"))
        assertEquals(logs, db.joblog().recent())
        assertArrayEquals(byteArrayOf(9, 8), File(root, "gate/audio.aac").readBytes())
        assertEquals(listOf("audio.aac"), File(root, "gate").list()!!.toList())

        val ready = OrationCreation(db, root).prepareGate("Legacy bundle.") as GatePreparation.Ready
        assertEquals("Legacy bundle.", ready.cleanedSource)
        assertEquals(row, ready.row)
        assertEquals(row, db.orations().byId("gate"))
        assertEquals(logs, db.joblog().recent())
        assertArrayEquals(byteArrayOf(9, 8), File(root, "gate/audio.aac").readBytes())
    }

    @Test
    fun `startup sweep removes abandoned pending preparation and preserves every row directory`() {
        seedGate()
        val abandoned = File(root, "abandoned").also { it.mkdir() }
        File(abandoned, "source.pending").writeText("interrupted")
        val published = File(root, "rowless").also { it.mkdir() }
        File(published, "source.md").writeText("not committed")
        OrationCreation(db, root).sweepOrphans()
        assertFalse(abandoned.exists())
        assertFalse(published.exists())
        assertTrue(File(root, "gate/source.md").exists())
        assertEquals(1, db.joblog().count("abandoned", "deleted"))
        assertEquals(1, db.joblog().count("rowless", "deleted"))
        assertEquals(0, db.joblog().count("gate", "deleted"))
        assertTrue(db.joblog().recent().filter { it.event == "deleted" }.all { it.detail == "orphan cleanup" })
    }

    @Test
    fun `unexpected publisher errors remain distinguishable from storage failures`() {
        val creation = OrationCreation(db, root, SourcePublisher { _, _, _ -> error("programming bug") })
        val error = assertThrows(IllegalStateException::class.java) { creation.create("Source", "Name", "af_heart") }
        assertEquals("programming bug", error.message)
        assertTrue(db.orations().all().isEmpty())
        assertTrue(root.listFiles()!!.isEmpty())
    }

    private fun seedGate(): OrationEntity {
        val row = OrationEntity(
            "gate", "Retained title", "STOPPED", "af_heart", 900, 20,
            chunksDone = 3, audioBytes = 2, narratedMs = 1234, createdAt = 1, updatedAt = 2,
            playheadMs = 5,
        )
        db.orations().insert(row)
        JobLog(db.joblog()).log("created", row.title, row.id)
        val directory = File(root, "gate").also { it.mkdir() }
        File(directory, "source.md").writeText("Saved source.")
        File(directory, "audio.aac").writeBytes(byteArrayOf(9, 8))
        return row
    }
}
