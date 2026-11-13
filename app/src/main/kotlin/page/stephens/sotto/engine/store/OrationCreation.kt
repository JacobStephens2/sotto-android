package page.stephens.sotto.engine.store

import android.database.sqlite.SQLiteException
import page.stephens.sotto.engine.text.Intake
import page.stephens.sotto.engine.text.TextPipeline
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

sealed interface CreationResult {
    data class Created(val id: String) : CreationResult
    data object Empty : CreationResult
    data class TooLarge(val bytes: Int) : CreationResult
    data class StorageFailure(val cause: Exception) : CreationResult
}

sealed interface GatePreparation {
    data class Ready(val row: OrationEntity, val cleanedSource: String) : GatePreparation
    data class StorageFailure(val cause: Exception) : GatePreparation
}

// One instance coordinates source publication and orphan classification for this process.
class OrationCreation internal constructor(
    private val db: SottoDatabase,
    private val root: File,
    private val publisher: SourcePublisher,
    private val nextId: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(db: SottoDatabase, root: File) : this(db, root, DurableSourcePublisher)

    private val publication = Any()

    fun create(text: String, title: String, voice: String): CreationResult = synchronized(publication) {
        val checked = Intake.check(text)
        val source = when (checked) {
            Intake.Checked.Empty -> return@synchronized CreationResult.Empty
            is Intake.Checked.TooLarge -> return@synchronized CreationResult.TooLarge(checked.bytes)
            is Intake.Checked.Ok -> checked.text
        }
        try {
            val id = nextId()
            if (db.orations().byId(id) != null) throw IOException("Oration already exists")
            withSource(id, source, reuse = false) { saved ->
                val now = clock()
                insert(OrationEntity(
                    id = id, title = title, status = "QUEUED", voiceId = voice,
                    words = wordCount(saved), chunksTotal = null, createdAt = now, updatedAt = now,
                ), "voice=$voice")
            }
            CreationResult.Created(id)
        } catch (e: Exception) {
            if (!isStorageFailure(e)) throw e
            CreationResult.StorageFailure(e)
        }
    }

    fun prepareGate(bundledText: String): GatePreparation = synchronized(publication) {
        try {
            val prior = db.orations().byId("gate")
            withSource("gate", bundledText, reuse = true) { saved ->
                val cleaned = TextPipeline.cleanMarkdown(saved)
                val now = clock()
                val row = prior ?: OrationEntity(
                    id = "gate", title = "Phase 1 gate", status = "QUEUED", voiceId = "af_heart",
                    words = wordCount(cleaned), chunksTotal = null, createdAt = now, updatedAt = now,
                ).also { insert(it, "") }
                GatePreparation.Ready(row, cleaned)
            }
        } catch (e: Exception) {
            if (!isStorageFailure(e)) throw e
            GatePreparation.StorageFailure(e)
        }
    }

    fun sweepOrphans() = synchronized(publication) {
        root.listFiles()?.filter { it.isDirectory && db.orations().byId(it.name) == null }?.forEach {
            val removed = try { it.deleteRecursively() } catch (_: SecurityException) { false }
            JobLog(db.joblog(), clock).log(if (removed) "deleted" else "error", it.name, it.name, "orphan cleanup")
        }
    }

    private fun insert(row: OrationEntity, detail: String) = db.runInTransaction {
        db.orations().insert(row)
        JobLog(db.joblog(), clock).log("created", row.title, row.id, detail)
    }

    private fun <T> withSource(id: String, text: String, reuse: Boolean, use: (String) -> T): T {
        val directory = File(root, id)
        var ownsDirectory = false
        var ownsSource = false
        var pending: File? = null
        var committed = false
        val source = File(directory, "source.md")
        try {
            if (!root.isDirectory && !root.mkdirs()) throw IOException("Could not create orations directory")
            if (!reuse || !directory.isDirectory) {
                if (!directory.mkdir()) throw IOException("Could not create new oration directory")
                ownsDirectory = true
            }
            if (!source.exists()) {
                pending = File.createTempFile("source.", ".pending", directory)
                ownsSource = true
                publisher.publish(pending, source, text)
            }
            return use(source.readText()).also { committed = true }
        } finally {
            // Cleanup is best effort and can only remove preparation owned by this attempt.
            runCatching { pending?.delete() }
            if (!committed) {
                if (ownsDirectory) runCatching { directory.deleteRecursively() }
                else if (ownsSource) runCatching { source.delete() }
            }
        }
    }

    private fun wordCount(text: String) = text.trim().split(Regex("\\s+")).size

    private fun isStorageFailure(e: Exception) = e is IOException || e is SQLiteException || e is SecurityException
}

internal fun interface SourcePublisher {
    fun publish(pending: File, source: File, text: String)
}

internal object DurableSourcePublisher : SourcePublisher {
    override fun publish(pending: File, source: File, text: String) {
        FileOutputStream(pending).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!pending.renameTo(source)) throw IOException("Could not publish oration source")
    }
}
