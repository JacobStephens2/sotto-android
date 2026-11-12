package page.stephens.sotto.engine.narrate

import page.stephens.sotto.engine.store.FileAudioSink
import page.stephens.sotto.engine.store.JobLog
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.RoomProgressStore
import page.stephens.sotto.engine.store.SottoDatabase
import page.stephens.sotto.engine.text.TextPipeline
import java.io.File
import java.io.FileOutputStream

// Called only by the retained worker, including source, progress, log and encoder work.
internal class DiagnosticExecution(
    private val db: SottoDatabase,
    private val root: File,
    private val encoderFactory: (Int) -> PcmEncoder,
    private val clock: () -> Long,
    private val environment: DiagnosticEnvironment,
) {
    fun gate(text: String, synth: Synthesizer, stopped: () -> Boolean, emit: (DiagnosticEvent) -> Unit) {
        val dao = db.orations()
        val log = JobLog(db.joblog(), clock)
        val title = "Phase 1 gate"
        val started = clock()
        try {
            val directory = File(root, "gate").also { it.mkdirs() }
            val source = File(directory, "source.md")
            // Existing source wins over a different bundle on a later app version.
            if (!source.exists()) FileOutputStream(source).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            val cleaned = TextPipeline.cleanMarkdown(source.readText())
            val prior = dao.byId("gate")
            if (prior == null) {
                dao.insert(OrationEntity(
                    id = "gate", title = title, status = "QUEUED", voiceId = "af_heart",
                    words = cleaned.trim().split(Regex("\\s+")).size, chunksTotal = null,
                    createdAt = clock(), updatedAt = clock()))
                log.log("created", title, "gate")
            }
            log.log(if (prior == null || prior.chunksDone == 0) "started" else "resumed", title, "gate",
                "from chunk ${prior?.chunksDone ?: 0}")
            emit(DiagnosticEvent.Status("gate: resuming from chunk ${prior?.chunksDone ?: 0}"))
            dao.setStatus("gate", "NARRATING", clock())
            val result = FileAudioSink(File(directory, "audio.aac")).use { sink ->
                NarrationEngine(synth, encoderFactory(synth.sampleRate), RoomProgressStore(dao, clock), clock)
                    .narrate("gate", cleaned, "af_heart", sink, shouldStop = stopped,
                        onProgress = { done, total ->
                            dao.setChunksTotal("gate", total, clock())
                            log.log("chunk-done", title, "gate", "$done/$total")
                            emit(DiagnosticEvent.Status("gate: chunk $done / $total · thermal ${environment.thermal()} · batt ${environment.batteryPct()}%"))
                        })
            }
            val verdict = when (result) {
                is NarrationResult.Complete -> {
                    dao.setStatus("gate", "COMPLETE", clock())
                    log.log("completed", title, "gate", "${result.chunks}ch ${result.audioBytes}B narratedMs=${result.narratedMs}")
                    "GATE COMPLETE: ${result.chunks} chunks, ${result.audioBytes / 1048576}MB, " +
                        "narrated %.1f min this+prior runs, wall %.1f min this run"
                            .format(result.narratedMs / 60000.0, (clock() - started) / 60000.0)
                }
                is NarrationResult.Stopped -> {
                    dao.setStatus("gate", "STOPPED", clock())
                    log.log("stopped", title, "gate", "${result.chunksDone}/${result.totalChunks}")
                    "gate stopped at ${result.chunksDone}/${result.totalChunks} (re-tap to resume)"
                }
                is NarrationResult.StorageFull -> {
                    dao.setStatus("gate", "STOPPED", clock(), NarrationCoordinator.STORAGE_FULL)
                    log.log("stopped", title, "gate", "storage full at ${result.chunksDone}/${result.totalChunks}")
                    "gate stopped: storage full at ${result.chunksDone}/${result.totalChunks} (free space, re-tap to resume)"
                }
                is NarrationResult.Failed -> throw result.cause
            }
            emit(DiagnosticEvent.Line(verdict))
        } catch (t: Throwable) {
            dao.setStatus("gate", "ERROR", clock(), t.message)
            log.log("error", title, "gate", t.toString().take(160))
            throw t
        }
    }

    fun measure(request: DiagnosticRequest.Measure, threads: Int, synth: Synthesizer,
        stopped: () -> Boolean, emit: (DiagnosticEvent) -> Unit) {
        val chunks = measurementChunks(request.text)
        emit(DiagnosticEvent.Line("start: ${request.text.trim().split(Regex("\\s+")).size} words/loop, ${chunks.size} chunks"))
        val battery0 = environment.batteryPct()
        val wall0 = environment.elapsedMs()
        val up0 = environment.uptimeMs()
        val deadline = wall0 + request.minutes * 60_000L
        var generateMs = 0L
        var audioSeconds = 0.0
        var count = 0
        var loop = 0
        var peakPss = 0L
        outer@ while (!stopped() && loop < request.loops &&
            (request.minutes == 0 || environment.elapsedMs() < deadline)) {
            for (text in chunks) {
                if (stopped() || (request.minutes > 0 && environment.elapsedMs() > deadline)) break@outer
                val start = environment.elapsedMs()
                val samples = synth.synthesize(text, "lab_sid_0")
                val duration = environment.elapsedMs() - start
                generateMs += duration
                count++
                val seconds = samples.size.toDouble() / synth.sampleRate
                audioSeconds += seconds
                peakPss = maxOf(peakPss, environment.pssKb())
                emit(DiagnosticEvent.Audio(samples, synth.sampleRate))
                emit(DiagnosticEvent.Status(("chunk $count · inst %.2fx · cum %.2fx · %.0fs audio · " +
                    "thermal ${environment.thermal()} · batt ${environment.batteryPct()}%% · peakPSS %dMB")
                    .format(seconds / (duration / 1000.0), audioSeconds / (generateMs / 1000.0), audioSeconds, peakPss / 1024)))
                if (count % 10 == 0) emit(DiagnosticEvent.Line(
                    "n=%d cumRTF=%.2fx audio=%.0fs wall=%.0fs batt=%d%% thermal=%s pssMB=%d"
                        .format(count, audioSeconds / (generateMs / 1000.0), audioSeconds,
                            (environment.elapsedMs() - wall0) / 1000.0, environment.batteryPct(),
                            environment.thermal(), peakPss / 1024)))
            }
            loop++
        }
        val wallSeconds = (environment.elapsedMs() - wall0) / 1000.0
        val measurement = Measurement(count, audioSeconds, generateMs, wallSeconds,
            wallSeconds - (environment.uptimeMs() - up0) / 1000.0,
            battery0, environment.batteryPct(), environment.thermal(), peakPss, threads,
            when {
                stopped() -> "stopped/superseded"
                request.minutes > 0 && environment.elapsedMs() >= deadline -> "deadline reached"
                else -> "loops done"
            })
        emit(DiagnosticEvent.Measured(measurement))
        emit(DiagnosticEvent.Line(measurement.summary()))
    }

    // Keep the spike's sentence packing and limit, separate from the server pipeline.
    private fun measurementChunks(text: String): List<String> {
        val output = arrayListOf<String>()
        val buffer = StringBuilder()
        for (sentence in text.split(Regex("(?<=[.!?])\\s+"))) {
            if (buffer.length + sentence.length > 1000 && buffer.isNotEmpty()) {
                output.add(buffer.toString())
                buffer.clear()
            }
            if (buffer.isNotEmpty()) buffer.append(' ')
            buffer.append(sentence)
        }
        if (buffer.isNotEmpty()) output.add(buffer.toString())
        return output
    }
}
