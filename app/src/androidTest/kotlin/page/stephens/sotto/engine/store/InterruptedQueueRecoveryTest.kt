package page.stephens.sotto.engine.store

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import page.stephens.sotto.engine.narrate.Progress

@RunWith(AndroidJUnit4::class)
class InterruptedQueueRecoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "interrupted-queue-recovery.db"
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
    fun recoveryRetainsOrdinaryProgressAndRequestOrderAcrossReopen() {
        val interrupted = row("interrupted", status = "NARRATING", queuedAt = 80)
        val queued = row("queued", status = "QUEUED", queuedAt = 70).copy(createdAt = 300)
        db.orations().insert(interrupted)
        db.orations().insert(queued)
        db.close()
        db = open()

        assertEquals(1, db.orations().requeueInterrupted(900))
        assertEquals(0, db.orations().requeueInterrupted(950))
        assertEquals(interrupted.copy(status = "QUEUED", updatedAt = 900), db.orations().byId("interrupted"))
        assertEquals(Progress(3, 1024, 5_000), RoomProgressStore(db.orations()).load("interrupted"))
        assertEquals("queued", db.orations().nextQueued()?.id)
        db.orations().delete("queued")
        assertEquals("interrupted", db.orations().nextQueued()?.id)
    }

    @Test
    fun interruptedOrLegacyQueuedGateRemainsAvailableOnlyForExplicitDiagnostics() {
        for (status in listOf("NARRATING", "QUEUED")) {
            val gate = row("gate", status = status, queuedAt = 10)
            db.orations().insert(gate)

            assertEquals(0, db.orations().requeueInterrupted(800))
            assertEquals(1, db.orations().stopInterruptedGate(900))
            assertEquals(0, db.orations().stopInterruptedGate(950))
            assertEquals(gate.copy(status = "STOPPED", updatedAt = 900), db.orations().byId("gate"))
            assertEquals(Progress(3, 1024, 5_000), RoomProgressStore(db.orations()).load("gate"))
            assertNull(db.orations().nextQueued())
            db.orations().delete("gate")
        }
    }

    private fun open(): SottoDatabase =
        Room.databaseBuilder(context, SottoDatabase::class.java, name)
            .addMigrations(SottoDatabase.MIGRATION_1_2)
            .build()

    private fun row(id: String, status: String, queuedAt: Long) = OrationEntity(
        id = id,
        title = "A saved oration",
        status = status,
        voiceId = "af_heart",
        words = 300,
        chunksTotal = 6,
        chunksDone = 3,
        audioBytes = 1024,
        audioDurationMs = 12_000,
        narratedMs = 5_000,
        createdAt = 20,
        updatedAt = 30,
        queuedAt = queuedAt,
        playheadMs = 4_000,
        errorMessage = "Retained interruption detail",
        serverName = "saved.md",
        serverMtime = 25,
    )
}
