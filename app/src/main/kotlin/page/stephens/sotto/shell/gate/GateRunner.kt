package page.stephens.sotto.shell.gate

// The Phase 1 exit gate, runnable from the spike app: narrate the bundled
// document (assets/gate/encyclical.md, not committed) end-to-end through the
// REAL stack - TextPipeline -> SherpaSynthesizer -> AdtsAacEncoder ->
// NarrationEngine -> FileAudioSink - with progress in Room. Killing the process
// mid-run and tapping the gate again must resume from the last durable chunk
// (the engine truncates and continues; the joblog records the resume point).

import android.content.Context
import page.stephens.sotto.engine.narrate.NarrationEngine
import page.stephens.sotto.engine.narrate.NarrationResult
import page.stephens.sotto.engine.store.FileAudioSink
import page.stephens.sotto.engine.store.JobLog
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.RoomProgressStore
import page.stephens.sotto.engine.store.SottoDatabase
import page.stephens.sotto.engine.text.TextPipeline
import page.stephens.sotto.shell.synth.AdtsAacEncoder
import page.stephens.sotto.shell.synth.SherpaSynthesizer
import java.io.File

class GateRunner(private val context: Context) {

    val audioFile = File(context.filesDir, "orations/gate/audio.aac")

    fun gateTextOrNull(): String? = runCatching {
        context.assets.open("gate/encyclical.md").use { it.readBytes().decodeToString() }
    }.getOrNull()

    fun reset() {
        val db = SottoDatabase.open(context)
        try { db.orations().delete("gate") } finally { db.close() }
        audioFile.delete()
    }

    fun run(
        text: String,
        threads: Int,
        shouldStop: () -> Boolean,
        onStatus: (String) -> Unit,
    ): String {
        val cleaned = TextPipeline.cleanMarkdown(text)
        val now = { System.currentTimeMillis() }
        val db = SottoDatabase.open(context)
        try {
            val dao = db.orations()
            val log = JobLog(db.joblog())
            val prior = dao.byId("gate")
            if (prior == null) {
                dao.insert(OrationEntity(
                    id = "gate", title = "Phase 1 gate", status = "QUEUED", voiceId = "af_heart",
                    words = cleaned.trim().split(Regex("\\s+")).size, chunksTotal = null,
                    createdAt = now(), updatedAt = now()))
                log.log("created", "Phase 1 gate", "gate")
            } else {
                log.log("resumed", "Phase 1 gate", "gate", "from chunk ${prior.chunksDone}")
                onStatus("gate: resuming from chunk ${prior.chunksDone}")
            }
            dao.setStatus("gate", "NARRATING", now())
            val started = now()
            SherpaSynthesizer(context, numThreads = threads).use { synth ->
                val result = FileAudioSink(audioFile).use { sink ->
                    NarrationEngine(synth, AdtsAacEncoder(synth.sampleRate), RoomProgressStore(dao))
                        .narrate("gate", cleaned, "af_heart", sink, shouldStop = shouldStop,
                            onProgress = { d, t -> onStatus("gate: chunk $d / $t") })
                }
                return when (result) {
                    is NarrationResult.Complete -> {
                        dao.setStatus("gate", "COMPLETE", now())
                        log.log("completed", "Phase 1 gate", "gate",
                            "${result.chunks}ch ${result.audioBytes}B narratedMs=${result.narratedMs}")
                        "GATE COMPLETE: ${result.chunks} chunks, ${result.audioBytes / 1048576}MB, " +
                            "narrated %.1f min this+prior runs, wall %.1f min this run"
                                .format(result.narratedMs / 60000.0, (now() - started) / 60000.0)
                    }
                    is NarrationResult.Stopped -> {
                        dao.setStatus("gate", "STOPPED", now())
                        log.log("stopped", "Phase 1 gate", "gate", "${result.chunksDone}/${result.totalChunks}")
                        "gate stopped at ${result.chunksDone}/${result.totalChunks} (re-tap to resume)"
                    }
                    is NarrationResult.Failed -> {
                        dao.setStatus("gate", "ERROR", now(), result.cause.message)
                        log.log("error", "Phase 1 gate", "gate", result.cause.toString().take(160))
                        "gate FAILED at chunk ${result.chunksDone}: ${result.cause}"
                    }
                }
            }
        } finally {
            db.close()
        }
    }
}
