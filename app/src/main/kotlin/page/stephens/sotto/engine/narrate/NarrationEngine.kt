package page.stephens.sotto.engine.narrate

// The narration core: text -> chunks -> synthesizer -> growing audio file, with
// per-chunk durability and resume (the sotto server's run_job/resume design,
// device-local; see docs/11-data-model.md invariants 1-2). Pure JVM logic against
// interfaces: the shell provides the sherpa-onnx synthesizer, the MediaCodec
// encoder, and the Room-backed progress store.

import page.stephens.sotto.engine.text.TextPipeline

/** Produces PCM for one chunk. Implemented by sherpa-onnx in the shell. */
interface Synthesizer {
    val sampleRate: Int
    fun synthesize(text: String, voiceId: String): FloatArray
}

/** Encodes PCM to the oration's container format. Tests use an identity codec;
 *  the shell uses hardware AAC (or LAME, per the SPIKE-001 container decision). */
interface PcmEncoder {
    fun encode(samples: FloatArray): ByteArray
}

/** The oration's growing audio artifact. Append-only with truncate-to-offset for
 *  resume; sync() must be durable before progress advances (invariant 1). */
interface AudioSink {
    fun length(): Long
    fun truncate(toBytes: Long)
    fun append(bytes: ByteArray)
    fun sync()
}

/** Durable progress record. chunksDone and audioBytes move together (invariant 2). */
interface ProgressStore {
    fun load(orationId: String): Progress?
    fun save(orationId: String, progress: Progress)
}

data class Progress(val chunksDone: Int, val audioBytes: Long, val narratedMs: Long)

sealed class NarrationResult {
    data class Complete(val chunks: Int, val audioBytes: Long, val narratedMs: Long) : NarrationResult()
    data class Stopped(val chunksDone: Int, val totalChunks: Int) : NarrationResult()
    data class Failed(val chunksDone: Int, val cause: Throwable) : NarrationResult()
}

class NarrationEngine(
    private val synthesizer: Synthesizer,
    private val encoder: PcmEncoder,
    private val store: ProgressStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Narrate [cleanedText] into [sink]. If the store holds progress for
     * [orationId], resumes from the last durable chunk: the sink is truncated to
     * the persisted byte offset (dropping any partial trailing chunk) and
     * synthesis continues from chunksDone. Returns when complete, stopped (via
     * [shouldStop]), or failed; in every case the store reflects exactly what
     * the sink durably holds.
     */
    fun narrate(
        orationId: String,
        cleanedText: String,
        voiceId: String,
        sink: AudioSink,
        chunkLimit: Int = TextPipeline.KOKORO_CHUNK_LIMIT,
        shouldStop: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): NarrationResult {
        val chunks = TextPipeline.chunk(cleanedText, chunkLimit)
        val prior = store.load(orationId)
        var done = 0
        var narratedMs = 0L
        if (prior != null && prior.chunksDone in 1..chunks.size && prior.audioBytes <= sink.length()) {
            sink.truncate(prior.audioBytes)   // drop any half-written trailing chunk
            done = prior.chunksDone
            narratedMs = prior.narratedMs     // accumulate across resumes (fixes the server's undercount)
        } else {
            sink.truncate(0L)
            store.save(orationId, Progress(0, 0L, 0L))
        }
        try {
            for (i in done until chunks.size) {
                if (shouldStop()) return NarrationResult.Stopped(done, chunks.size)
                val t0 = clock()
                val pcm = synthesizer.synthesize(chunks[i], voiceId)
                val bytes = encoder.encode(pcm)
                sink.append(bytes)
                sink.sync()                                   // durable BEFORE progress advances
                narratedMs += clock() - t0
                done = i + 1
                store.save(orationId, Progress(done, sink.length(), narratedMs))
                onProgress(done, chunks.size)
            }
        } catch (t: Throwable) {
            return NarrationResult.Failed(done, t)            // store already matches the sink
        }
        return NarrationResult.Complete(chunks.size, sink.length(), narratedMs)
    }
}
