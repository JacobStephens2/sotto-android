package page.stephens.sotto.engine.store

// Room schema per docs/11-data-model.md. Room is authoritative for metadata and
// progress; the filesystem holds the audio and source text under
// filesDir/orations/<id>/. Sync columns stay null/false in the `local` flavor
// (invariant 5).

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import page.stephens.sotto.engine.narrate.Progress
import page.stephens.sotto.engine.narrate.ProgressStore

enum class OrationStatus { QUEUED, NARRATING, STOPPED, COMPLETE, ERROR }

@Entity(tableName = "orations")
data class OrationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val status: String,                 // OrationStatus.name
    val voiceId: String,
    val words: Int,
    val chunksTotal: Int?,
    val chunksDone: Int = 0,
    val audioBytes: Long = 0,
    val audioDurationMs: Long = 0,
    val narratedMs: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val playheadMs: Long = 0,
    val errorMessage: String? = null,
    // sync flavor only; always null/false in `local` (invariant 5)
    val serverName: String? = null,
    val serverMtime: Long? = null,
    val downloaded: Boolean = true,
)

@Entity(tableName = "joblog")
data class JobLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val orationId: String?,
    val title: String,
    val event: String,    // created|started|resumed|chunk-done|stopped|completed|error|deleted|exported|renamed
    val detail: String,
)

@Dao
interface OrationDao {
    @Query("SELECT * FROM orations WHERE id = :id")
    fun byId(id: String): OrationEntity?

    @Query("SELECT * FROM orations ORDER BY createdAt DESC")
    fun all(): List<OrationEntity>

    @Query("SELECT * FROM orations ORDER BY createdAt DESC")
    fun allFlow(): kotlinx.coroutines.flow.Flow<List<OrationEntity>>

    @Query("UPDATE orations SET chunksTotal = :total, updatedAt = :now WHERE id = :id")
    fun setChunksTotal(id: String, total: Int, now: Long)

    @Query("UPDATE orations SET playheadMs = :pos, updatedAt = :now WHERE id = :id")
    fun setPlayhead(id: String, pos: Long, now: Long)

    @Query("SELECT * FROM orations WHERE id = :id")
    fun byIdFlow(id: String): kotlinx.coroutines.flow.Flow<OrationEntity?>

    @Query("SELECT * FROM orations WHERE status = 'QUEUED' ORDER BY createdAt ASC LIMIT 1")
    fun nextQueued(): OrationEntity?

    @Query("UPDATE orations SET title = :title, updatedAt = :now WHERE id = :id")
    fun rename(id: String, title: String, now: Long)

    // Per-device seconds-per-word samples for the calibrated estimate
    // (the server's calibrated_rates, local; median taken in Kotlin).
    @Query("SELECT narratedMs * 1.0 / 1000 / words FROM orations WHERE status = 'COMPLETE' AND words >= 50 AND narratedMs > 0")
    fun completedRates(): List<Double>

    @Insert
    fun insert(o: OrationEntity)

    @Update
    fun update(o: OrationEntity)

    @Query("UPDATE orations SET chunksDone = :done, audioBytes = :bytes, narratedMs = :narratedMs, updatedAt = :now WHERE id = :id")
    fun saveProgress(id: String, done: Int, bytes: Long, narratedMs: Long, now: Long)

    @Query("UPDATE orations SET status = :status, updatedAt = :now, errorMessage = :error WHERE id = :id")
    fun setStatus(id: String, status: String, now: Long, error: String? = null)

    // Startup recovery (resume_jobs design): anything mid-flight reverts to QUEUED
    // with its durable progress intact.
    @Query("UPDATE orations SET status = 'QUEUED', updatedAt = :now WHERE status = 'NARRATING'")
    fun requeueInterrupted(now: Long): Int

    @Query("DELETE FROM orations WHERE id = :id")
    fun delete(id: String)
}

@Dao
interface JobLogDao {
    @Insert
    fun append(e: JobLogEntity)

    @Query("SELECT * FROM joblog ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int = 200): List<JobLogEntity>

    @Query("SELECT COUNT(*) FROM joblog WHERE orationId = :orationId AND event = :event")
    fun count(orationId: String, event: String): Int
}

@Database(entities = [OrationEntity::class, JobLogEntity::class], version = 1, exportSchema = true)
abstract class SottoDatabase : RoomDatabase() {
    abstract fun orations(): OrationDao
    abstract fun joblog(): JobLogDao

    companion object {
        // Process-wide singleton: the activity's Flow collectors only see the
        // service's writes if both use the same instance (Room invalidation is
        // per-instance). Never closed; it lives as long as the process.
        @Volatile private var instance: SottoDatabase? = null
        fun open(context: Context): SottoDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, SottoDatabase::class.java, "sotto.db",
                ).build().also { instance = it }
            }
    }
}

/** The engine's ProgressStore, backed by the orations row (invariant 2: chunksDone
 *  and audioBytes move in one statement). */
class RoomProgressStore(
    private val dao: OrationDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProgressStore {
    override fun load(orationId: String): Progress? =
        dao.byId(orationId)?.takeIf { it.chunksDone > 0 }
            ?.let { Progress(it.chunksDone, it.audioBytes, it.narratedMs) }

    override fun save(orationId: String, progress: Progress) =
        dao.saveProgress(orationId, progress.chunksDone, progress.audioBytes, progress.narratedMs, clock())
}

/** Append-only traceability (boundary 5). Never leaves the device. */
class JobLog(private val dao: JobLogDao, private val clock: () -> Long = System::currentTimeMillis) {
    fun log(event: String, title: String, orationId: String? = null, detail: String = "") =
        dao.append(JobLogEntity(at = clock(), orationId = orationId, title = title.take(80), event = event, detail = detail))
}
