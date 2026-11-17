package page.stephens.sotto.engine.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class DownloadedPublicationTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), SottoDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val files = Files.createTempDirectory("download-publication").toFile()
    private val root = File(files, "orations")
    private val serial = AtomicInteger()

    @After
    fun close() {
        db.close()
        files.deleteRecursively()
    }

    @Test
    fun `preparation rereads identity target and availability without changing artifacts`() {
        val publication = publication()
        assertEquals(DownloadPreparation.Rejected(DownloadRejection.MISSING),
            publication.prepareDownload("remote", "essay.aac"))
        val row = seed()
        assertEquals(DownloadPreparation.Rejected(DownloadRejection.CHANGED_TARGET),
            publication.prepareDownload(row.id, "changed.aac"))
        assertTrue(publication.prepareDownload(row.id, "essay.aac") is DownloadPreparation.Ready)
        val saved = row.copy(downloaded = true, audioBytes = 4)
        db.orations().update(saved)
        finalFile("audio.aac").writeText("kept")
        assertEquals(DownloadPreparation.AlreadyAvailable, publication.prepareDownload(row.id, "essay.aac"))
        assertEquals(saved, db.orations().byId(row.id))
        assertEquals("kept", finalFile("audio.aac").readText())
        assertTrue(db.joblog().recent().isEmpty())
    }

    @Test
    fun `download commits actual length and one log without changing timestamps or requiring text`() {
        val row = seed()
        val publication = publication()
        val attempt = attempt(publication)
        assertEquals(DownloadPublication.Published,
            publication.publishDownload(attempt, stage("audio"), null) { true })
        assertEquals(row.copy(downloaded = true, audioBytes = 5), db.orations().byId(row.id))
        assertEquals("audio", finalFile("audio.aac").readText())
        assertFalse(File(root, "remote/source.md").exists())
        assertEquals(1, db.joblog().count(row.id, "downloaded"))
        assertEquals(DownloadPublication.AlreadyAvailable,
            publication.publishDownload(attempt, stage("wrong"), stage("wrong source")) { true })
        assertEquals("audio", finalFile("audio.aac").readText())
        assertFalse(File(root, "remote/source.md").exists())
        assertEquals(1, db.joblog().count(row.id, "downloaded"))
    }

    @Test
    fun `partial promotion and sync faults restore old finals and preserve unrelated files`() {
        val row = seed()
        for (fault in listOf("partial audio", "audio.aac", "source.md", "sync audio.aac", "sync source.md")) {
            finalFile("audio.aac").writeText("old audio")
            finalFile("source.md").writeText("old source")
            finalFile("keep.txt").writeText("unrelated")
            val publication = publication(object : StagedFilePublisher {
                override fun promote(staged: File, target: File) {
                    if (fault == "partial audio" && target.name == "audio.aac") {
                        target.writeText("partial")
                        throw IOException(fault)
                    }
                    DurableStagedFilePublisher.promote(staged, target)
                    if (fault == target.name) throw IOException(fault)
                }
                override fun sync(target: File) {
                    DurableStagedFilePublisher.sync(target)
                    if (fault == "sync ${target.name}") throw IOException(fault)
                }
            })
            assertTrue(fault, publication.publishDownload(attempt(publication), stage("new audio"), stage("new source")) {
                true
            } is DownloadPublication.StorageFailure)
            assertEquals(row, db.orations().byId(row.id))
            assertEquals(0, db.joblog().count(row.id, "downloaded"))
            assertEquals("old audio", finalFile("audio.aac").readText())
            assertEquals("old source", finalFile("source.md").readText())
            assertEquals("unrelated", finalFile("keep.txt").readText())
            assertEquals(setOf("audio.aac", "source.md", "keep.txt"), File(root, row.id).list()!!.toSet())
        }
    }

    @Test
    fun `copy fallback publishes durable bytes and a failed staged removal restores previous files`() {
        val row = seed()
        val publication = publication()
        val attempt = attempt(publication)
        finalFile("audio.aac").writeText("retained")
        fun copyOnly(text: String, removable: Boolean): File = object : File(stage(text).path) {
            override fun renameTo(dest: File) = false
            override fun delete() = removable && super.delete()
        }
        assertTrue(publication.publishDownload(attempt, copyOnly("failed copy", false), null) {
            true
        } is DownloadPublication.StorageFailure)
        assertEquals("retained", finalFile("audio.aac").readText())
        assertEquals(row, db.orations().byId(row.id))
        assertEquals(0, db.joblog().count(row.id, "downloaded"))
        assertEquals(DownloadPublication.Published,
            publication.publishDownload(attempt, copyOnly("copied audio", true), copyOnly("copied text", true)) { true })
        assertEquals("copied audio", finalFile("audio.aac").readText())
        assertEquals("copied text", finalFile("source.md").readText())
        assertEquals(12L, db.orations().byId(row.id)!!.audioBytes)
        assertEquals(1, db.joblog().count(row.id, "downloaded"))
    }

    @Test
    fun `rejected downloaded log rolls back Room and both replacements`() {
        val row = seed()
        finalFile("audio.aac").writeText("retained audio")
        finalFile("source.md").writeText("retained source")
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_download BEFORE INSERT ON joblog WHEN NEW.event = 'downloaded' " +
                "BEGIN SELECT RAISE(ABORT, 'log unavailable'); END",
        )
        val publication = publication()
        assertTrue(publication.publishDownload(attempt(publication), stage("new"), stage("new text")) {
            true
        } is DownloadPublication.StorageFailure)
        assertEquals(row, db.orations().byId(row.id))
        assertTrue(db.joblog().recent().isEmpty())
        assertEquals("retained audio", finalFile("audio.aac").readText())
        assertEquals("retained source", finalFile("source.md").readText())
    }

    @Test
    fun `accepted commit survives caller invalidation and failed best effort backup cleanup`() {
        val row = seed()
        finalFile("audio.aac").writeText("preceding")
        val directory = File(root, row.id)
        var valid = true
        val publication = OrationPublication(db, root, DurableSourcePublisher, clock = {
            // Both validity reads have accepted this operation; invalidate before its log/commit completes.
            valid = false
            check(directory.setWritable(false))
            42L
        })
        try {
            assertEquals(DownloadPublication.Published,
                publication.publishDownload(attempt(publication), stage("accepted"), stage("accepted source")) { valid })
            assertFalse(valid)
            assertEquals("accepted", finalFile("audio.aac").readText())
            assertEquals("accepted source", finalFile("source.md").readText())
            assertEquals(row.copy(downloaded = true, audioBytes = 8), db.orations().byId(row.id))
            assertEquals(1, db.joblog().count(row.id, "downloaded"))
            assertEquals(42L, db.joblog().recent().single().at)
            assertTrue(directory.list()!!.any { it.endsWith(".backup") })
        } finally {
            assertTrue(directory.setWritable(true))
        }
    }

    @Test
    fun `caller invalidation inside Room restores files and leaves no downloaded fact`() {
        val row = seed()
        finalFile("audio.aac").writeText("retained")
        val publication = publication()
        var checks = 0
        assertEquals(DownloadPublication.Rejected(DownloadRejection.INVALID_CALLER),
            publication.publishDownload(attempt(publication), stage("new"), stage("new text")) { ++checks == 1 })
        assertEquals(2, checks)
        assertEquals(row, db.orations().byId(row.id))
        assertEquals("retained", finalFile("audio.aac").readText())
        assertFalse(File(root, "remote/source.md").exists())
        assertTrue(db.joblog().recent().isEmpty())
    }

    @Test
    fun `missing changed and removed recreated rows reject late attempts before touching finals`() {
        val original = seed()
        val publication = publication()
        val attempt = attempt(publication)
        finalFile("audio.aac").writeText("kept")
        db.orations().update(original.copy(serverName = "other.aac"))
        assertEquals(DownloadPublication.Rejected(DownloadRejection.CHANGED_TARGET),
            publication.publishDownload(attempt, stage("wrong"), null) { true })
        assertEquals("kept", finalFile("audio.aac").readText())
        db.orations().delete(original.id)
        assertEquals(DownloadPublication.Rejected(DownloadRejection.MISSING),
            publication.publishDownload(attempt, stage("wrong"), null) { true })
        db.orations().insert(original)
        assertEquals(RemovalResult.Removed, publication.remove(original.id))
        // Equal timestamps demonstrate that removal generation, rather than timestamp luck, fences recreation.
        db.orations().insert(original)
        finalFile("audio.aac").writeText("recreated")
        assertEquals(DownloadPublication.Rejected(DownloadRejection.REMOVED),
            publication.publishDownload(attempt, stage("wrong"), stage("wrong source")) { true })
        assertEquals("recreated", finalFile("audio.aac").readText())
        assertEquals(original, db.orations().byId(original.id))
        assertEquals(0, db.joblog().count(original.id, "downloaded"))
        assertEquals(1, db.joblog().count(original.id, "deleted"))
    }

    @Test
    fun `failed publication rolls back before a waiting newer attempt can succeed`() {
        seed()
        val placed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val publication = publication(object : StagedFilePublisher by DurableStagedFilePublisher {
            override fun promote(staged: File, target: File) {
                DurableStagedFilePublisher.promote(staged, target)
                if (calls.incrementAndGet() == 1) {
                    placed.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    throw IOException("old attempt failed after placement")
                }
            }
        })
        val old = attempt(publication)
        val newer = attempt(publication)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<DownloadPublication> { publication.publishDownload(old, stage("old"), null) { true } }
            assertTrue(placed.await(5, TimeUnit.SECONDS))
            val waiting = CountDownLatch(1)
            val second = pool.submit<DownloadPublication> {
                waiting.countDown()
                publication.publishDownload(newer, stage("new bytes"), stage("new source")) { true }
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { second.get(200, TimeUnit.MILLISECONDS) }
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS) is DownloadPublication.StorageFailure)
            assertEquals(DownloadPublication.Published, second.get(5, TimeUnit.SECONDS))
            assertEquals("new bytes", finalFile("audio.aac").readText())
            assertEquals("new source", finalFile("source.md").readText())
            assertEquals(9L, db.orations().byId("remote")!!.audioBytes)
            assertTrue(db.orations().byId("remote")!!.downloaded)
            assertEquals(1, db.joblog().count("remote", "downloaded"))
        } finally {
            release.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `publication winning over deletion is removed only after commit without resurrection`() {
        withPlacedPublication { publication, attempt, pool, placed, release ->
            val publishing = pool.submit<DownloadPublication> {
                publication.publishDownload(attempt, stage("new"), null) { true }
            }
            assertTrue(placed.await(5, TimeUnit.SECONDS))
            val deleting = CountDownLatch(1)
            val removal = pool.submit<RemovalResult> { deleting.countDown(); publication.remove("remote") }
            assertTrue(deleting.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { removal.get(200, TimeUnit.MILLISECONDS) }
            assertNotNull(db.orations().byId("remote"))
            release.countDown()
            assertEquals(DownloadPublication.Published, publishing.get(5, TimeUnit.SECONDS))
            assertEquals(RemovalResult.Removed, removal.get(5, TimeUnit.SECONDS))
            assertNull(db.orations().byId("remote"))
            assertFalse(File(root, "remote").exists())
            assertEquals(listOf("deleted", "downloaded"), db.joblog().recent().map { it.event })
            assertEquals(DownloadPublication.Rejected(DownloadRejection.REMOVED),
                publication.publishDownload(attempt, stage("late"), null) { true })
            assertFalse(File(root, "remote").exists())
        }
    }

    @Test
    fun `sweep waits for download publication and preserves its committed artifacts`() {
        withPlacedPublication { publication, attempt, pool, placed, release ->
            val publishing = pool.submit<DownloadPublication> {
                publication.publishDownload(attempt, stage("audio"), stage("text")) { true }
            }
            assertTrue(placed.await(5, TimeUnit.SECONDS))
            val sweeping = CountDownLatch(1)
            val swept = CountDownLatch(1)
            val sweep = pool.submit { sweeping.countDown(); publication.sweepOrphans(); swept.countDown() }
            assertTrue(sweeping.await(5, TimeUnit.SECONDS))
            assertFalse(swept.await(200, TimeUnit.MILLISECONDS))
            release.countDown()
            assertEquals(DownloadPublication.Published, publishing.get(5, TimeUnit.SECONDS))
            sweep.get(5, TimeUnit.SECONDS)
            assertEquals("audio", finalFile("audio.aac").readText())
            assertEquals("text", finalFile("source.md").readText())
            assertTrue(db.orations().byId("remote")!!.downloaded)
            assertEquals(1, db.joblog().count("remote", "downloaded"))
            assertEquals(0, db.joblog().count("remote", "deleted"))
        }
    }

    @Test
    fun `fresh Room target identity and availability are revalidated after file placement`() {
        val row = seed()
        for (changed in listOf(row.copy(serverName = "other.aac"), row.copy(createdAt = 11), row.copy(downloaded = true))) {
            db.orations().update(row)
            finalFile("audio.aac").writeText("retained")
            val publication = publication(object : StagedFilePublisher by DurableStagedFilePublisher {
                override fun promote(staged: File, target: File) {
                    DurableStagedFilePublisher.promote(staged, target)
                    db.orations().update(changed)
                }
            })
            assertEquals(DownloadPublication.Rejected(DownloadRejection.CHANGED_TARGET),
                publication.publishDownload(attempt(publication), stage("new"), null) { true })
            assertEquals(changed, db.orations().byId(row.id))
            assertEquals("retained", finalFile("audio.aac").readText())
            assertEquals(0, db.joblog().count(row.id, "downloaded"))
        }
    }

    @Test
    fun `removal failure advances generation and startup sweep retries the rowless directory`() {
        val row = seed()
        val publication = publication()
        val old = attempt(publication)
        val audio = finalFile("audio.aac").also { it.writeText("old") }
        val directory = audio.parentFile!!
        assertTrue(directory.setWritable(false))
        try {
            assertTrue(publication.remove(row.id) is RemovalResult.StorageFailure)
            assertNull(db.orations().byId(row.id))
            assertEquals("old", audio.readText())
            assertEquals(0, db.joblog().count(row.id, "deleted"))
            assertEquals(1, db.joblog().count(row.id, "error"))
            db.orations().insert(row)
            assertEquals(DownloadPublication.Rejected(DownloadRejection.REMOVED),
                publication.publishDownload(old, stage("late"), null) { true })
            db.orations().delete(row.id)
        } finally {
            assertTrue(directory.setWritable(true))
        }
        publication.sweepOrphans()
        assertFalse(directory.exists())
        assertEquals(1, db.joblog().count(row.id, "deleted"))
        assertEquals(1, db.joblog().count(row.id, "error"))
    }

    @Test
    fun `unexpected staged publisher errors propagate after rolling back`() {
        seed()
        val publication = publication(object : StagedFilePublisher by DurableStagedFilePublisher {
            override fun promote(staged: File, target: File) {
                DurableStagedFilePublisher.promote(staged, target)
                error("publisher bug")
            }
        })
        assertThrows(IllegalStateException::class.java) {
            publication.publishDownload(attempt(publication), stage("new"), null) { true }
        }
        assertFalse(File(root, "remote").exists())
        assertFalse(db.orations().byId("remote")!!.downloaded)
        assertTrue(db.joblog().recent().isEmpty())
    }

    private fun withPlacedPublication(
        test: (OrationPublication, OrationPublication.DownloadAttempt, java.util.concurrent.ExecutorService,
            CountDownLatch, CountDownLatch) -> Unit,
    ) {
        seed()
        val placed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val publication = publication(object : StagedFilePublisher by DurableStagedFilePublisher {
            override fun promote(staged: File, target: File) {
                DurableStagedFilePublisher.promote(staged, target)
                if (target.name == "audio.aac") {
                    placed.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
        })
        val pool = Executors.newFixedThreadPool(2)
        try {
            test(publication, attempt(publication), pool, placed, release)
        } finally {
            release.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun publication(adapter: StagedFilePublisher = DurableStagedFilePublisher) =
        OrationPublication(db, root, DurableSourcePublisher, stagedFiles = adapter)

    private fun attempt(publication: OrationPublication) =
        (publication.prepareDownload("remote", "essay.aac") as DownloadPreparation.Ready).attempt

    private fun stage(text: String) = File(files, "staging/${serial.incrementAndGet()}").also {
        it.parentFile!!.mkdirs()
        it.writeText(text)
    }

    private fun finalFile(name: String) = File(root, "remote/$name").also { it.parentFile!!.mkdirs() }

    private fun seed(): OrationEntity = OrationEntity(
        "remote", "Essay", "COMPLETE", "af_heart", 12, null,
        audioBytes = 900, createdAt = 10, updatedAt = 20, serverName = "essay.aac", downloaded = false,
    ).also { db.orations().insert(it) }
}
