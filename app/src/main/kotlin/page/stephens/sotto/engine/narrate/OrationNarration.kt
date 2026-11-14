package page.stephens.sotto.engine.narrate

import page.stephens.sotto.engine.store.FileAudioSink
import page.stephens.sotto.engine.store.GatePreparation
import page.stephens.sotto.engine.store.JobLog
import page.stephens.sotto.engine.store.OrationCreation
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.RoomProgressStore
import page.stephens.sotto.engine.store.SottoDatabase
import page.stephens.sotto.engine.text.TextPipeline
import java.io.File

internal sealed interface OrationNarrationRequest {
    data class Ordinary(val id: String) : OrationNarrationRequest
    data class Gate(val bundledText: String) : OrationNarrationRequest
}

internal sealed interface SynthesizerAcquisition {
    data class Ready(val synthesizer: Synthesizer) : SynthesizerAcquisition
    data object Stopped : SynthesizerAcquisition
}

internal sealed interface OrationNarrationObservation {
    data class Prepared(val row: OrationEntity) : OrationNarrationObservation
    data class ChunkCommitted(val row: OrationEntity, val total: Int) : OrationNarrationObservation
}

internal sealed interface OrationNarrationOutcome {
    data object Missing : OrationNarrationOutcome
    data object StoppedBeforeExecution : OrationNarrationOutcome
    data class Executed(val result: NarrationResult) : OrationNarrationOutcome
    data class Failed(val cause: Throwable) : OrationNarrationOutcome
}

// Synchronous work on the retained lane; the coordinator owns models and run validity.
internal class OrationNarration(
    private val db: SottoDatabase,
    private val root: File,
    private val creation: OrationCreation,
    private val encoderFactory: (Int) -> PcmEncoder,
    private val clock: () -> Long,
) {
    private val dao = db.orations()
    private val log = JobLog(db.joblog(), clock)

    fun narrate(
        request: OrationNarrationRequest,
        stopped: () -> Boolean,
        acquire: () -> SynthesizerAcquisition,
        observe: (OrationNarrationObservation) -> Unit = {},
    ): OrationNarrationOutcome {
        var established: OrationEntity? = null
        try {
            val cleaned: String
            val row = when (request) {
                is OrationNarrationRequest.Ordinary -> {
                    val saved = dao.byId(request.id) ?: return OrationNarrationOutcome.Missing
                    established = saved
                    if (stopped()) return stopBeforeExecution(saved, "before the first chunk")
                    cleaned = TextPipeline.cleanMarkdown(File(root, "${saved.id}/source.md").readText())
                    saved
                }
                is OrationNarrationRequest.Gate -> {
                    val prepared = when (val result = creation.prepareGate(request.bundledText)) {
                        is GatePreparation.Ready -> result
                        is GatePreparation.StorageFailure -> return OrationNarrationOutcome.Failed(result.cause)
                    }
                    cleaned = prepared.cleanedSource
                    prepared.row.copy(title = "Phase 1 gate", voiceId = "af_heart")
                        .also { established = it }
                }
            }
            dao.setStatus(row.id, "NARRATING", clock())
            log.log(if (row.chunksDone == 0) "started" else "resumed", row.title, row.id,
                if (request is OrationNarrationRequest.Gate) "from chunk ${row.chunksDone}" else "")
            observe(OrationNarrationObservation.Prepared(row))
            val synth = when (val acquired = acquire()) {
                is SynthesizerAcquisition.Ready -> acquired.synthesizer
                SynthesizerAcquisition.Stopped -> return stopBeforeExecution(row, "before model load")
            }
            val result = FileAudioSink(File(root, "${row.id}/audio.aac")).use { sink ->
                NarrationEngine(synth, encoderFactory(synth.sampleRate), RoomProgressStore(dao, clock), clock)
                    .narrate(row.id, cleaned, row.voiceId, sink, shouldStop = stopped,
                        onProgress = { _, total ->
                            dao.setChunksTotal(row.id, total, clock())
                            val saved = checkNotNull(dao.byId(row.id))
                            log.log("chunk-done", row.title, row.id, "${saved.chunksDone}/$total")
                            observe(OrationNarrationObservation.ChunkCommitted(saved, total))
                        })
            }
            recordOutcome(row, result, request is OrationNarrationRequest.Gate)
            return OrationNarrationOutcome.Executed(result)
        } catch (t: Throwable) {
            // Preparation/model failures before a gate row exists remain diagnostic only.
            established?.let { row ->
                dao.setStatus(row.id, "ERROR", clock(), t.message)
                log.log("error", row.title, row.id, t.toString().take(160))
            }
            return OrationNarrationOutcome.Failed(t)
        }
    }

    private fun stopBeforeExecution(row: OrationEntity, detail: String): OrationNarrationOutcome {
        dao.setStatus(row.id, "STOPPED", clock())
        log.log("stopped", row.title, row.id, detail)
        return OrationNarrationOutcome.StoppedBeforeExecution
    }

    private fun recordOutcome(row: OrationEntity, result: NarrationResult, gate: Boolean) {
        when (result) {
            is NarrationResult.Complete -> {
                dao.setStatus(row.id, "COMPLETE", clock())
                val detail = "${result.chunks}ch ${result.audioBytes}B"
                log.log("completed", row.title, row.id,
                    if (gate) "$detail narratedMs=${result.narratedMs}" else detail)
            }
            is NarrationResult.Stopped -> {
                dao.setStatus(row.id, "STOPPED", clock())
                log.log("stopped", row.title, row.id, "${result.chunksDone}/${result.totalChunks}")
            }
            is NarrationResult.StorageFull -> {
                dao.setStatus(row.id, "STOPPED", clock(), NarrationCoordinator.STORAGE_FULL)
                log.log("stopped", row.title, row.id, "storage full at ${result.chunksDone}/${result.totalChunks}")
            }
            is NarrationResult.Failed -> throw result.cause
        }
    }
}
