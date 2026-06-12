package page.stephens.sotto.engine

// The Phase 1 gate test, instrumented: a narration interrupted by process death
// (simulated as: stop mid-run, close the database, reopen everything fresh)
// resumes through the Room-backed store to a byte-identical artifact, and the
// startup recovery requeues mid-flight rows. Also asserts job-log coverage.

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import page.stephens.sotto.engine.narrate.NarrationEngine
import page.stephens.sotto.engine.narrate.NarrationResult
import page.stephens.sotto.engine.narrate.PcmEncoder
import page.stephens.sotto.engine.narrate.Synthesizer
import page.stephens.sotto.engine.store.FileAudioSink
import page.stephens.sotto.engine.store.JobLog
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.RoomProgressStore
import page.stephens.sotto.engine.store.SottoDatabase
import java.io.File

private class StubSynth : Synthesizer {
    override val sampleRate = 24000
    override fun synthesize(text: String, voiceId: String) =
        FloatArray(48) { i -> ((text.hashCode() * 17 + i) % 997) / 997f }
}

private object PcmBytes : PcmEncoder {
    override fun encode(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, f ->
            val v = (f * 32767).toInt()
            out[i * 2] = (v and 0xFF).toByte(); out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}

@RunWith(AndroidJUnit4::class)
class RoomResumeTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val text = (1..30).joinToString(" ") { "Sentence $it carries a steady number of words along." }

    private fun freshDb(name: String): SottoDatabase =
        Room.databaseBuilder(ctx, SottoDatabase::class.java, name).build()
            .also { ctx.deleteDatabase(name); }.let {
                it.close(); Room.databaseBuilder(ctx, SottoDatabase::class.java, name).build()
            }

    @Test
    fun narrationResumesByteIdenticallyAcrossDatabaseReopen() {
        val dbName = "test-resume.db"
        ctx.deleteDatabase(dbName)

        // Reference: uninterrupted run with a throwaway store.
        val refFile = File(ctx.filesDir, "ref.pcm").apply { delete() }
        run {
            val db = freshDb("test-ref.db")
            db.orations().insert(row("ref"))
            FileAudioSink(refFile).use { sink ->
                NarrationEngine(StubSynth(), PcmBytes, RoomProgressStore(db.orations()))
                    .narrate("ref", text, "af_heart", sink, chunkLimit = 150)
            }
            db.close()
        }

        // Interrupted run: stop after 2 chunks, then "process death" - close db.
        val outFile = File(ctx.filesDir, "out.pcm").apply { delete() }
        var db = Room.databaseBuilder(ctx, SottoDatabase::class.java, dbName).build()
        db.orations().insert(row("o1"))
        val log = JobLog(db.joblog())
        log.log("created", "T", "o1")
        var n = 0
        val r1 = FileAudioSink(outFile).use { sink ->
            NarrationEngine(StubSynth(), PcmBytes, RoomProgressStore(db.orations()))
                .narrate("o1", text, "af_heart", sink, chunkLimit = 150,
                    shouldStop = { n >= 2 }, onProgress = { d, _ -> n = d })
        }
        assertTrue(r1 is NarrationResult.Stopped)
        db.orations().setStatus("o1", "NARRATING", System.currentTimeMillis())
        log.log("stopped", "T", "o1")
        db.close()                                  // process death

        // Crash garbage past the durable offset.
        outFile.appendBytes(byteArrayOf(1, 2, 3))

        // Reopen fresh (new process): startup recovery requeues, then resume.
        db = Room.databaseBuilder(ctx, SottoDatabase::class.java, dbName).build()
        assertEquals(1, db.orations().requeueInterrupted(System.currentTimeMillis()))
        assertEquals("QUEUED", db.orations().byId("o1")!!.status)
        JobLog(db.joblog()).log("resumed", "T", "o1")
        val r2 = FileAudioSink(outFile).use { sink ->
            NarrationEngine(StubSynth(), PcmBytes, RoomProgressStore(db.orations()))
                .narrate("o1", text, "af_heart", sink, chunkLimit = 150)
        }
        assertTrue(r2 is NarrationResult.Complete)
        assertArrayEquals(refFile.readBytes(), outFile.readBytes())

        // Job-log coverage (constitution boundary 5).
        for (event in listOf("created", "stopped", "resumed")) {
            assertEquals("event '$event'", 1, db.joblog().count("o1", event))
        }
        db.close()
    }

    private fun row(id: String) = OrationEntity(
        id = id, title = "T", status = "QUEUED", voiceId = "af_heart",
        words = 30, chunksTotal = null,
        createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
    )
}
