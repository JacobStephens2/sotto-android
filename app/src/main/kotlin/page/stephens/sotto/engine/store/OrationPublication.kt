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

// One process owner serializes final files, matching Room facts, and pre-commit rollback.
class OrationPublication internal constructor(
    private val db: SottoDatabase,
    private val root: File,
    private val publisher: SourcePublisher,
    private val nextId: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val stagedFiles: StagedFilePublisher = DurableStagedFilePublisher,
) {
    constructor(db: SottoDatabase, root: File) : this(db, root, DurableSourcePublisher)

    private val publication = Any()
    private val removalGenerations = mutableMapOf<String, Long>()

    class DownloadAttempt internal constructor(
        internal val owner: OrationPublication,
        internal val id: String,
        internal val createdAt: Long,
        internal val serverName: String,
        internal val generation: Long,
    )

    fun prepareDownload(id: String, expectedServerName: String): DownloadPreparation = synchronized(publication) {
        try {
            val row = db.orations().byId(id)
                ?: return@synchronized DownloadPreparation.Rejected(DownloadRejection.MISSING)
            if (row.serverName != expectedServerName) {
                return@synchronized DownloadPreparation.Rejected(DownloadRejection.CHANGED_TARGET)
            }
            if (row.downloaded) return@synchronized DownloadPreparation.AlreadyAvailable
            DownloadPreparation.Ready(DownloadAttempt(this, id, row.createdAt, expectedServerName, generation(id)))
        } catch (e: Exception) {
            if (!isStorageFailure(e)) throw e
            DownloadPreparation.StorageFailure(e)
        }
    }

    // The caller enters off-main, outside Room and its session lock. Validity only samples admission facts.
    fun publishDownload(
        attempt: DownloadAttempt,
        audio: File,
        source: File?,
        callerValid: () -> Boolean,
    ): DownloadPublication = synchronized(publication) {
        try {
            publishDownloadLocked(attempt, audio, source, callerValid)
        } catch (e: Exception) {
            if (!isStorageFailure(e)) throw e
            DownloadPublication.StorageFailure(e)
        }
    }

    private fun publishDownloadLocked(
        attempt: DownloadAttempt,
        audio: File,
        source: File?,
        callerValid: () -> Boolean,
    ): DownloadPublication {
        val replacements = mutableListOf<Replacement>()
        val directory = File(root, attempt.id)
        var ownsDirectory = false
        var committed = false
        return try {
            val row = validate(attempt, callerValid)
            if (row.downloaded) return DownloadPublication.AlreadyAvailable
            if (!directory.isDirectory) {
                if (!directory.mkdirs()) throw IOException("Could not create destination directory")
                ownsDirectory = true
            }
            fun replace(staged: File, name: String) {
                val target = File(directory, name)
                val replacement = Replacement(target)
                replacements.add(replacement)
                if (target.exists()) {
                    val backup = File(directory, ".$name.${UUID.randomUUID()}.backup")
                    if (!target.renameTo(backup)) throw IOException("Could not preserve $name")
                    replacement.backup = backup
                }
                // Promotion can place bytes and then fail, so register ownership before calling the adapter.
                replacement.ownsTarget = true
                stagedFiles.promote(staged, target)
                stagedFiles.sync(target)
            }
            replace(audio, "audio.aac")
            source?.let { replace(it, "source.md") }
            val bytes = File(directory, "audio.aac").length()
            db.runInTransaction {
                val current = validate(attempt, callerValid)
                if (current.downloaded) throw DownloadRejected(DownloadRejection.CHANGED_TARGET)
                check(db.orations().setDownloaded(current.id, bytes) == 1)
                JobLog(db.joblog(), clock).log("downloaded", current.title, current.id, attempt.serverName)
            }
            committed = true
            DownloadPublication.Published
        } catch (e: DownloadRejected) {
            DownloadPublication.Rejected(e.reason)
        } finally {
            if (committed) {
                replacements.forEach { runCatching { it.backup?.delete() } }
            } else {
                // Still under the monitor: a waiting publication cannot inherit paths before rollback finishes.
                replacements.asReversed().forEach { replacement ->
                    if (replacement.ownsTarget) replacement.target.delete()
                    replacement.backup?.let {
                        if (!it.renameTo(replacement.target)) throw IOException("Could not restore ${replacement.target.name}")
                    }
                }
                if (ownsDirectory && directory.list()?.isEmpty() == true) directory.delete()
            }
        }
    }

    fun remove(id: String): RemovalResult = synchronized(publication) {
        removalGenerations[id] = generation(id) + 1
        try {
            val row = db.orations().byId(id)
            db.orations().delete(id)
            if (!File(root, id).deleteRecursively()) throw IOException("Could not remove oration files")
            JobLog(db.joblog(), clock).log("deleted", row?.title ?: id, id)
            RemovalResult.Removed
        } catch (e: Exception) {
            if (!isStorageFailure(e)) throw e
            runCatching { JobLog(db.joblog(), clock).log("error", id, id, "deletion failed: $e") }
            RemovalResult.StorageFailure(e)
        }
    }

    private fun generation(id: String) = removalGenerations[id] ?: 0L

    private fun validate(attempt: DownloadAttempt, callerValid: () -> Boolean): OrationEntity {
        if (attempt.owner !== this || attempt.generation != generation(attempt.id)) {
            throw DownloadRejected(DownloadRejection.REMOVED)
        }
        val row = db.orations().byId(attempt.id) ?: throw DownloadRejected(DownloadRejection.MISSING)
        if (row.createdAt != attempt.createdAt || row.serverName != attempt.serverName) {
            throw DownloadRejected(DownloadRejection.CHANGED_TARGET)
        }
        if (!callerValid()) throw DownloadRejected(DownloadRejection.INVALID_CALLER)
        return row
    }

    private class DownloadRejected(val reason: DownloadRejection) : RuntimeException()
    private class Replacement(val target: File, var backup: File? = null, var ownsTarget: Boolean = false)

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

enum class DownloadRejection { MISSING, REMOVED, CHANGED_TARGET, INVALID_CALLER }

sealed interface DownloadPreparation {
    data class Ready(val attempt: OrationPublication.DownloadAttempt) : DownloadPreparation
    data object AlreadyAvailable : DownloadPreparation
    data class Rejected(val reason: DownloadRejection) : DownloadPreparation
    data class StorageFailure(val cause: Exception) : DownloadPreparation
}

sealed interface DownloadPublication {
    data object Published : DownloadPublication
    data object AlreadyAvailable : DownloadPublication
    data class Rejected(val reason: DownloadRejection) : DownloadPublication
    data class StorageFailure(val cause: Exception) : DownloadPublication
}

sealed interface RemovalResult {
    data object Removed : RemovalResult
    data class StorageFailure(val cause: Exception) : RemovalResult
}

internal interface StagedFilePublisher {
    fun promote(staged: File, target: File)
    fun sync(target: File)
}

internal object DurableStagedFilePublisher : StagedFilePublisher {
    override fun promote(staged: File, target: File) {
        if (staged.renameTo(target)) return
        staged.inputStream().use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        if (!staged.delete()) throw IOException("Could not remove staged ${staged.name}")
    }

    override fun sync(target: File) {
        FileOutputStream(target, true).use { it.fd.sync() }
    }
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
