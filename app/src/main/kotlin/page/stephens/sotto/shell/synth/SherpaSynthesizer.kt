package page.stephens.sotto.shell.synth

// The real Synthesizer: Kokoro-82M via sherpa-onnx. Loads once per construction
// (the service holds one for its lifetime). Voice ids are the product's seven
// (docs/02-prd.md F4); sherpa addresses Kokoro voices by integer sid, so this is
// where the mapping lives.

import android.content.Context
import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import page.stephens.sotto.engine.narrate.Synthesizer
import java.io.File

class SherpaSynthesizer(
    context: Context,
    numThreads: Int = 4,
    private val sidOf: (String) -> Int = DEFAULT_SIDS,
) : Synthesizer, AutoCloseable {

    private val tts: OfflineTts

    init {
        val assets: AssetManager = context.assets
        tts = OfflineTts(
            assets,
            OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = "models/model.int8.onnx",
                        voices = "models/voices.bin",
                        tokens = "models/tokens.txt",
                        dataDir = materialize(context, "models/espeak-ng-data"),
                        lexicon = "models/lexicon-us-en.txt,models/lexicon-zh.txt",
                        dictDir = materialize(context, "models/dict"),
                    ),
                    numThreads = numThreads,
                    provider = "cpu",
                ),
            ),
        )
    }

    override val sampleRate: Int get() = tts.sampleRate()

    override fun synthesize(text: String, voiceId: String): FloatArray =
        tts.generate(text = text, sid = sidOf(voiceId), speed = 1.0f).samples

    override fun close() = tts.release()

    private fun materialize(context: Context, assetDir: String): String {
        // espeak-ng-data and dict must be real files on disk, not assets.
        val out = File(context.filesDir, assetDir)
        if (out.exists()) return out.absolutePath
        fun copy(p: String, dst: File) {
            val children = context.assets.list(p) ?: emptyArray()
            if (children.isEmpty()) {
                dst.parentFile?.mkdirs()
                context.assets.open(p).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            } else children.forEach { copy("$p/$it", File(dst, it)) }
        }
        copy(assetDir, out)
        return out.absolutePath
    }

    companion object {
        // Authoritative ids from sherpa-onnx scripts/kokoro/v1.0/generate_voices_bin.py
        // (the script that builds voices.bin): af_heart=3, not the 0 we guessed -
        // sid 0 is af_alloy, which is what early builds actually spoke with.
        val DEFAULT_SIDS: (String) -> Int = { voiceId ->
            mapOf(
                "af_heart" to 3, "af_bella" to 2, "af_nicole" to 6,
                "am_adam" to 11, "am_michael" to 16,
                "bf_emma" to 21, "bm_george" to 26,
            )[voiceId] ?: 3
        }
    }
}
