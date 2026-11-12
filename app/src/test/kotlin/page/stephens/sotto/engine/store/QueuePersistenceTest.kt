package page.stephens.sotto.engine.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QueuePersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "queue-persistence-test.db"
    private lateinit var db: SottoDatabase

    @Before
    fun openDatabase() {
        context.deleteDatabase(name)
        db = open()
    }

    @After
    fun closeDatabase() {
        db.close()
        context.deleteDatabase(name)
    }

    @Test
    fun `ordinary pickup excludes the diagnostic gate and breaks request ties by ID after reopen`() {
        db.orations().insert(row("gate", createdAt = 1))
        db.orations().insert(row("zeta"))
        db.orations().insert(row("alpha"))
        db.close()
        db = open()

        assertEquals("alpha", db.orations().nextQueued()?.id)
        db.orations().delete("alpha")
        assertEquals("zeta", db.orations().nextQueued()?.id)
        db.orations().delete("zeta")
        assertNull(db.orations().nextQueued())
    }

    @Test
    fun `a retry persists its new request time and keeps progress while duplicate admission stays coalesced`() {
        db.orations().insert(row("already-queued", createdAt = 300).copy(queuedAt = 90))
        val retries = listOf("STOPPED", "ERROR").mapIndexed { index, status ->
            row("retry-$index", createdAt = 10).copy(
                status = status,
                queuedAt = 10,
                chunksDone = 3,
                audioBytes = 1024,
                audioDurationMs = 12_000,
                narratedMs = 5_000,
                playheadMs = 4_000,
                errorMessage = "Previous interruption",
                serverName = "saved.md",
                serverMtime = 75,
            )
        }
        retries.forEachIndexed { index, original ->
            db.orations().insert(original)
            val requestedAt = 100L + index
            assertEquals(1, db.orations().enqueueRetry(original.id, requestedAt))
            assertEquals(0, db.orations().enqueueRetry(original.id, 200))
        }
        db.close()
        db = open()

        retries.forEachIndexed { index, original ->
            assertEquals(
                original.copy(status = "QUEUED", queuedAt = 100L + index, updatedAt = 100L + index, errorMessage = null),
                db.orations().byId(original.id),
            )
        }
        assertEquals("already-queued", db.orations().nextQueued()?.id)
        db.orations().delete("already-queued")
        assertEquals("retry-0", db.orations().nextQueued()?.id)
        db.orations().delete("retry-0")
        assertEquals("retry-1", db.orations().nextQueued()?.id)
    }

    private fun open(): SottoDatabase =
        Room.databaseBuilder(context, SottoDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

    private fun row(id: String, createdAt: Long = 100) = OrationEntity(
        id = id,
        title = "A saved oration",
        status = "QUEUED",
        voiceId = "af_heart",
        words = 300,
        chunksTotal = 6,
        createdAt = createdAt,
        updatedAt = 150,
    )
}
