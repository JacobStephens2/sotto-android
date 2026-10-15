package page.stephens.sotto.engine.narrate

// The resume contract, JVM-level: an interrupted-and-resumed narration produces
// a byte-identical artifact to an uninterrupted one - including when the crash
// left a half-written trailing chunk past the last durable offset. (The
// process-kill instrumented variant arrives with the Room store in Phase 1.)

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

private class FakeSynth : Synthesizer {
    override val sampleRate = 24000
    var calls = 0
    override fun synthesize(text: String, voiceId: String): FloatArray {
        calls++
        // Deterministic per-text output so byte-comparison is meaningful.
        return FloatArray(64) { i -> ((text.hashCode() * 31 + i) % 1000) / 1000f }
    }
}

private object IdentityEncoder : PcmEncoder {
    override fun encode(samples: FloatArray): ByteArray {
        val out = ByteArrayOutputStream()
        samples.forEach { f -> java.nio.ByteBuffer.allocate(4).putFloat(f).array().let(out::write) }
        return out.toByteArray()
    }
}

private class MemorySink : AudioSink {
    var data = ByteArray(0)
    var synced = 0L
    override fun length() = data.size.toLong()
    override fun truncate(toBytes: Long) { data = data.copyOf(toBytes.toInt()) }
    override fun append(bytes: ByteArray) { data += bytes }
    override fun sync() { synced = data.size.toLong() }
}

/** Writes half of the Nth append, then fails the way a full disk does; later
 *  appends succeed again (the user freed space). */
private class DiskFillsSink(private val failOnAppend: Int) : AudioSink {
    var data = ByteArray(0)
    private var appends = 0
    override fun length() = data.size.toLong()
    override fun truncate(toBytes: Long) { data = data.copyOf(toBytes.toInt()) }
    override fun sync() {}
    override fun append(bytes: ByteArray) {
        if (++appends == failOnAppend) {
            data += bytes.copyOf(bytes.size / 2)
            throw java.io.IOException("write failed: ENOSPC (No space left on device)")
        }
        data += bytes
    }
}

private class MemoryStore : ProgressStore {
    val map = HashMap<String, Progress>()
    override fun load(orationId: String) = map[orationId]
    override fun save(orationId: String, progress: Progress) { map[orationId] = progress }
}

class NarrationEngineTest {

    private val text = (1..40).joinToString(" ") {
        "Sentence number $it has a comfortable handful of words in it."
    }

    private fun engine(synth: FakeSynth = FakeSynth(), store: MemoryStore = MemoryStore()) =
        NarrationEngine(synth, IdentityEncoder, store) to store

    @Test
    fun `uninterrupted narration completes with store matching sink`() {
        val (eng, store) = engine()
        val sink = MemorySink()
        val r = eng.narrate("o1", text, "af_heart", sink, chunkLimit = 200)
        assertTrue(r is NarrationResult.Complete)
        assertEquals(sink.length(), store.map["o1"]!!.audioBytes)
    }

    @Test
    fun `resume after stop is byte-identical to an uninterrupted run`() {
        // Reference: clean run.
        val (ref, _) = engine()
        val refSink = MemorySink()
        ref.narrate("ref", text, "af_heart", refSink, chunkLimit = 200)

        // Interrupted: stop after 3 chunks, then resume with a fresh engine.
        val store = MemoryStore()
        val synth = FakeSynth()
        val sink = MemorySink()
        var n = 0
        val r1 = NarrationEngine(synth, IdentityEncoder, store)
            .narrate("o", text, "af_heart", sink, chunkLimit = 200, shouldStop = { n >= 3 },
                onProgress = { done, _ -> n = done })
        assertTrue(r1 is NarrationResult.Stopped)
        val r2 = NarrationEngine(FakeSynth(), IdentityEncoder, store)
            .narrate("o", text, "af_heart", sink, chunkLimit = 200)
        assertTrue(r2 is NarrationResult.Complete)
        assertArrayEquals(refSink.data, sink.data)
    }

    @Test
    fun `crash leaving a half-written trailing chunk is truncated on resume`() {
        val (ref, _) = engine()
        val refSink = MemorySink()
        ref.narrate("ref", text, "af_heart", refSink, chunkLimit = 200)

        val store = MemoryStore()
        val sink = MemorySink()
        var n = 0
        NarrationEngine(FakeSynth(), IdentityEncoder, store)
            .narrate("o", text, "af_heart", sink, chunkLimit = 200, shouldStop = { n >= 2 },
                onProgress = { done, _ -> n = done })
        sink.data += byteArrayOf(13, 37, 42, 99)   // garbage past the durable offset
        val r = NarrationEngine(FakeSynth(), IdentityEncoder, store)
            .narrate("o", text, "af_heart", sink, chunkLimit = 200)
        assertTrue(r is NarrationResult.Complete)
        assertArrayEquals(refSink.data, sink.data)
    }

    @Test
    fun `resume synthesizes only the remaining chunks`() {
        val store = MemoryStore()
        val sink = MemorySink()
        var n = 0
        NarrationEngine(FakeSynth(), IdentityEncoder, store)
            .narrate("o", text, "af_heart", sink, chunkLimit = 200, shouldStop = { n >= 3 },
                onProgress = { done, _ -> n = done })
        val total = page.stephens.sotto.engine.text.TextPipeline.chunk(text, 200).size
        val resumeSynth = FakeSynth()
        NarrationEngine(resumeSynth, IdentityEncoder, store).narrate("o", text, "af_heart", sink, chunkLimit = 200)
        assertEquals(total - 3, resumeSynth.calls)
    }

    @Test
    fun `synthesis failure leaves store consistent with sink and resume recovers`() {
        val (ref, _) = engine()
        val refSink = MemorySink()
        ref.narrate("ref", text, "af_heart", refSink, chunkLimit = 200)

        val store = MemoryStore()
        val sink = MemorySink()
        val flaky = object : Synthesizer {
            override val sampleRate = 24000
            var calls = 0
            override fun synthesize(text: String, voiceId: String): FloatArray {
                if (++calls == 4) throw RuntimeException("model hiccup")
                return FakeSynth().also { it.calls = 0 }.synthesize(text, voiceId)
            }
        }
        val r1 = NarrationEngine(flaky, IdentityEncoder, store).narrate("o", text, "af_heart", sink, chunkLimit = 200)
        assertTrue(r1 is NarrationResult.Failed)
        assertEquals(3, (r1 as NarrationResult.Failed).chunksDone)
        assertEquals(sink.length(), store.map["o"]!!.audioBytes)
        val r2 = NarrationEngine(FakeSynth(), IdentityEncoder, store).narrate("o", text, "af_heart", sink, chunkLimit = 200)
        assertTrue(r2 is NarrationResult.Complete)
        assertArrayEquals(refSink.data, sink.data)
    }

    @Test
    fun `narratedMs accumulates across resumes`() {
        var t = 0L
        val clock = { t += 50; t }
        val store = MemoryStore()
        val sink = MemorySink()
        var n = 0
        NarrationEngine(FakeSynth(), IdentityEncoder, store, clock)
            .narrate("o", text, "af_heart", sink, chunkLimit = 200, shouldStop = { n >= 3 },
                onProgress = { done, _ -> n = done })
        val afterStop = store.map["o"]!!.narratedMs
        assertTrue(afterStop > 0)
        val r = NarrationEngine(FakeSynth(), IdentityEncoder, store, clock)
            .narrate("o", text, "af_heart", sink, chunkLimit = 200)
        assertTrue((r as NarrationResult.Complete).narratedMs > afterStop)
    }

    @Test
    fun `storage full stops gracefully and keeps the durable partial`() {
        // PRD edge case: the disk fills mid-narration. The half-written chunk is
        // dropped, everything durable stays, and resume later is byte-identical.
        val (ref, _) = engine()
        val refSink = MemorySink()
        ref.narrate("ref", text, "af_heart", refSink, chunkLimit = 200)

        val store = MemoryStore()
        val sink = DiskFillsSink(failOnAppend = 4)
        val r = NarrationEngine(FakeSynth(), IdentityEncoder, store)
            .narrate("o", text, "af_heart", sink, chunkLimit = 200)
        assertTrue(r is NarrationResult.StorageFull)
        assertEquals(3, (r as NarrationResult.StorageFull).chunksDone)
        assertEquals(store.map["o"]!!.audioBytes, sink.length())

        NarrationEngine(FakeSynth(), IdentityEncoder, store)
            .narrate("o", text, "af_heart", sink, chunkLimit = 200)
        assertArrayEquals(refSink.data, sink.data)
    }

    @Test
    fun `an ordinary failure is not mistaken for storage full`() {
        val failing = object : Synthesizer {
            override val sampleRate = 24000
            override fun synthesize(text: String, voiceId: String): FloatArray = error("model blew up")
        }
        val r = NarrationEngine(failing, IdentityEncoder, MemoryStore())
            .narrate("o", text, "af_heart", MemorySink(), chunkLimit = 200)
        assertTrue(r is NarrationResult.Failed)
    }
}
