package page.stephens.sotto.engine.narrate

import page.stephens.sotto.engine.store.JobLog
import page.stephens.sotto.engine.store.OrationDao

// Both callers persist terminal outcomes in the retained execution lane.
internal class NarrationOutcomeRecorder(
    private val dao: OrationDao,
    private val log: JobLog,
    private val clock: () -> Long,
) {
    fun record(id: String, title: String, result: NarrationResult) {
        when (result) {
            is NarrationResult.Complete -> {
                dao.setStatus(id, "COMPLETE", clock())
                val detail = "${result.chunks}ch ${result.audioBytes}B"
                log.log("completed", title, id,
                    if (id == "gate") "$detail narratedMs=${result.narratedMs}" else detail)
            }
            is NarrationResult.Stopped -> {
                dao.setStatus(id, "STOPPED", clock())
                log.log("stopped", title, id, "${result.chunksDone}/${result.totalChunks}")
            }
            is NarrationResult.StorageFull -> {
                dao.setStatus(id, "STOPPED", clock(), NarrationCoordinator.STORAGE_FULL)
                log.log("stopped", title, id, "storage full at ${result.chunksDone}/${result.totalChunks}")
            }
            is NarrationResult.Failed -> throw result.cause
        }
    }

    fun recordFailure(id: String, title: String, cause: Throwable) {
        dao.setStatus(id, "ERROR", clock(), cause.message)
        log.log("error", title, id, cause.toString().take(160))
    }
}
