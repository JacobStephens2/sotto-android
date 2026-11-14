package page.stephens.sotto.engine.narrate

// Presentation and measurement on the retained lane; durable gates use shared narration.
internal class DiagnosticExecution(
    private val narration: OrationNarration,
    private val clock: () -> Long,
    private val environment: DiagnosticEnvironment,
) {
    fun gate(text: String, synth: Synthesizer, stopped: () -> Boolean,
        emit: (DiagnosticEvent) -> Unit, finish: (DiagnosticEvent) -> Unit) {
        val started = clock()
        val outcome = narration.narrate(OrationNarrationRequest.Gate(text), stopped,
            acquire = { SynthesizerAcquisition.Ready(synth) },
            observe = { observation ->
                when (observation) {
                    is OrationNarrationObservation.Prepared ->
                        emit(DiagnosticEvent.Status("gate: resuming from chunk ${observation.row.chunksDone}"))
                    is OrationNarrationObservation.ChunkCommitted ->
                        emit(DiagnosticEvent.Status("gate: chunk ${observation.row.chunksDone} / ${observation.total} · thermal ${environment.thermal()} · batt ${environment.batteryPct()}%"))
                }
            })
        when (outcome) {
            is OrationNarrationOutcome.Failed -> throw outcome.cause
            is OrationNarrationOutcome.Executed -> {
                val verdict = when (val result = outcome.result) {
                    is NarrationResult.Complete -> {
                        "GATE COMPLETE: ${result.chunks} chunks, ${result.audioBytes / 1048576}MB, " +
                            "narrated %.1f min this+prior runs, wall %.1f min this run"
                                .format(result.narratedMs / 60000.0, (clock() - started) / 60000.0)
                    }
                    is NarrationResult.Stopped -> {
                        "gate stopped at ${result.chunksDone}/${result.totalChunks} (re-tap to resume)"
                    }
                    is NarrationResult.StorageFull -> {
                        "gate stopped: storage full at ${result.chunksDone}/${result.totalChunks} (free space, re-tap to resume)"
                    }
                    is NarrationResult.Failed -> throw result.cause
                }
                finish(DiagnosticEvent.Line(verdict))
            }
            OrationNarrationOutcome.Missing, OrationNarrationOutcome.StoppedBeforeExecution -> Unit
        }
    }

    fun measure(request: DiagnosticRequest.Measure, threads: Int, synth: Synthesizer,
        stopped: () -> Boolean, emit: (DiagnosticEvent) -> Unit, finish: (DiagnosticEvent) -> Unit) {
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
        finish(DiagnosticEvent.Measured(measurement))
        finish(DiagnosticEvent.Line(measurement.summary()))
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
