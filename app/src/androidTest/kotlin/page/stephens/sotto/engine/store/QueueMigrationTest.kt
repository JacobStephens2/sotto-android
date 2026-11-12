package page.stephens.sotto.engine.store

import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import page.stephens.sotto.engine.narrate.Progress
import java.io.File

@RunWith(AndroidJUnit4::class)
class QueueMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "schema-v1-queue-migration.db"
    private val artifactDir = File(context.filesDir, "orations/migration-interrupted")
    private lateinit var db: SottoDatabase

    @Before
    fun prepareDatabase() {
        context.deleteDatabase(name)
        artifactDir.deleteRecursively()
    }

    @After
    fun closeDatabase() {
        if (::db.isInitialized) db.close()
        context.deleteDatabase(name)
        artifactDir.deleteRecursively()
    }

    @Test
    fun schemaV1UpgradePreservesMetadataCheckpointsListeningPositionsFilesAndQueueOrder() {
        val rows = listOf(
            row("migration-zeta", "QUEUED", 200),
            row("migration-alpha", "QUEUED", 200),
            row("migration-earliest", "QUEUED", 100),
            row("migration-interrupted", "NARRATING", 50),
            row("gate", "QUEUED", 1),
            row("migration-error", "ERROR", 300),
            row("migration-stopped", "STOPPED", 310).copy(errorMessage = null, serverName = null, serverMtime = null),
            row("migration-complete", "COMPLETE", 320).copy(chunksDone = 6),
            row("migration-server-only", "QUEUED", 1).copy(downloaded = false),
        )
        val savedLog = JobLogEntity(
            id = 41,
            at = 400,
            orationId = "migration-interrupted",
            title = "Saved title",
            event = "chunk-done",
            detail = "3/6",
        )
        val audio = byteArrayOf(2, 4, 6, 8, 10, 12, 99, 98)
        artifactDir.mkdirs()
        File(artifactDir, "source.md").writeText("# Saved source\nThe source survives the upgrade.")
        File(artifactDir, "audio.aac").writeBytes(audio)
        createV1(rows, savedLog)

        db = open()
        assertEquals(rows.toSet(), db.orations().all().toSet())
        db.close()
        db = open()

        assertEquals(rows.toSet(), db.orations().all().toSet())
        assertEquals(listOf(savedLog), db.joblog().recent())
        assertEquals(Progress(3, 6, 5_000), RoomProgressStore(db.orations()).load("migration-interrupted"))
        assertEquals("# Saved source\nThe source survives the upgrade.", File(artifactDir, "source.md").readText())
        assertArrayEquals(audio, File(artifactDir, "audio.aac").readBytes())
        assertEquals("migration-earliest", db.orations().nextQueued()?.id)
        db.orations().delete("migration-earliest")
        assertEquals("migration-alpha", db.orations().nextQueued()?.id)
        db.orations().delete("migration-alpha")
        assertEquals("migration-zeta", db.orations().nextQueued()?.id)
    }

    private fun createV1(rows: List<OrationEntity>, log: JobLogEntity) {
        val schema = instrumentation.context.assets
            .open("page.stephens.sotto.engine.store.SottoDatabase/1.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) old.execSQL(setup.getString(index))
            rows.forEach { old.insertOrThrow("orations", null, v1Values(it)) }
            old.insertOrThrow("joblog", null, ContentValues().apply {
                put("id", log.id)
                put("at", log.at)
                put("orationId", log.orationId)
                put("title", log.title)
                put("event", log.event)
                put("detail", log.detail)
            })
            old.version = 1
        }
    }

    private fun v1Values(row: OrationEntity) = ContentValues().apply {
        put("id", row.id)
        put("title", row.title)
        put("status", row.status)
        put("voiceId", row.voiceId)
        put("words", row.words)
        put("chunksTotal", row.chunksTotal)
        put("chunksDone", row.chunksDone)
        put("audioBytes", row.audioBytes)
        put("audioDurationMs", row.audioDurationMs)
        put("narratedMs", row.narratedMs)
        put("createdAt", row.createdAt)
        put("updatedAt", row.updatedAt)
        put("playheadMs", row.playheadMs)
        put("errorMessage", row.errorMessage)
        put("serverName", row.serverName)
        put("serverMtime", row.serverMtime)
        put("downloaded", row.downloaded)
    }

    private fun open(): SottoDatabase =
        Room.databaseBuilder(context, SottoDatabase::class.java, name)
            .addMigrations(SottoDatabase.MIGRATION_1_2)
            .build()

    private fun row(id: String, status: String, createdAt: Long) = OrationEntity(
        id = id,
        title = "Saved title",
        status = status,
        voiceId = "bf_emma",
        words = 300,
        chunksTotal = 6,
        chunksDone = 3,
        audioBytes = 6,
        audioDurationMs = 12_000,
        narratedMs = 5_000,
        createdAt = createdAt,
        updatedAt = 350,
        playheadMs = 4_000,
        errorMessage = "Retained interruption detail",
        serverName = "saved.md",
        serverMtime = 25,
    )
}
