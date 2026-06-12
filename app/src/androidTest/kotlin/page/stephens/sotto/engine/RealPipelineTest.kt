package page.stephens.sotto.engine

// Real-components smoke: Kokoro via sherpa-onnx + hardware AAC/ADTS, on the
// Android runtime. Asserts the audio is real (duration in a sane band for the
// word count), the ADTS stream is recognized by MediaExtractor, and encoding is
// deterministic (the property byte-identical resume rests on).

import android.media.MediaExtractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import page.stephens.sotto.shell.synth.AdtsAacEncoder
import page.stephens.sotto.shell.synth.SherpaSynthesizer
import java.io.File

@RunWith(AndroidJUnit4::class)
class RealPipelineTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun kokoroPlusAdtsProducesRecognizableDeterministicAudio() {
        SherpaSynthesizer(ctx, numThreads = 2).use { synth ->
            val text = "The quick brown fox jumps over the lazy dog. " +
                "A measured number beats an estimated one."
            val pcm = synth.synthesize(text, "af_heart")
            assertTrue("synthesis produced samples", pcm.isNotEmpty())
            val secs = pcm.size.toDouble() / synth.sampleRate
            assertTrue("duration $secs s sane for ~16 words", secs in 3.0..12.0)

            val enc = AdtsAacEncoder(synth.sampleRate)
            val a = enc.encode(pcm)
            val b = enc.encode(pcm)
            assertTrue("encoded non-empty", a.isNotEmpty())
            assertArrayEquals("deterministic encode (resume contract)", a, b)
            assertEquals("ADTS sync word", 0xFF, a[0].toInt() and 0xFF)
            assertEquals(0xF0, a[1].toInt() and 0xF0)

            val f = File(ctx.cacheDir, "probe.aac").apply { writeBytes(a) }
            val ex = MediaExtractor()
            try {
                ex.setDataSource(f.absolutePath)
                assertEquals(1, ex.trackCount)
                val fmt = ex.getTrackFormat(0)
                assertTrue(fmt.getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("audio/mp4a"))
            } finally {
                ex.release(); f.delete()
            }
        }
    }
}
