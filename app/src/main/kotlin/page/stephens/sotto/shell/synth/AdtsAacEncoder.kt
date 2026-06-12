package page.stephens.sotto.shell.synth

// The real PcmEncoder: hardware AAC-LC in ADTS framing. ADTS resolves the
// container question (10-architecture-overview.md "Audio format decision"):
// every frame is self-contained, so the oration file is appendable across
// sessions and truncatable at any chunk boundary - no MP4 moov fixups - and
// ExoPlayer/MediaPlayer play it natively. A fresh codec per encode() keeps
// output deterministic per chunk, which the byte-identical-resume contract
// (NarrationEngineTest) depends on.

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import page.stephens.sotto.engine.narrate.PcmEncoder
import java.io.ByteArrayOutputStream

class AdtsAacEncoder(
    private val sampleRate: Int = 24000,
    private val bitRate: Int = 64_000,
) : PcmEncoder {

    override fun encode(samples: FloatArray): ByteArray {
        val pcm = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, f ->
            val v = (f.coerceIn(-1f, 1f) * 32767).toInt()
            pcm[i * 2] = (v and 0xFF).toByte()
            pcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val out = ByteArrayOutputStream()
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            codec.configure(
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
                },
                null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            codec.start()
            var fed = 0
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (!inputDone) {
                    val inIx = codec.dequeueInputBuffer(10_000)
                    if (inIx >= 0) {
                        val buf = codec.getInputBuffer(inIx)!!
                        val n = minOf(buf.capacity(), pcm.size - fed)
                        buf.clear(); buf.put(pcm, fed, n)
                        fed += n
                        if (fed >= pcm.size) {
                            codec.queueInputBuffer(inIx, 0, n, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else codec.queueInputBuffer(inIx, 0, n, 0, 0)
                    }
                }
                val outIx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIx >= 0) {
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val frame = ByteArray(info.size)
                        codec.getOutputBuffer(outIx)!!.apply { position(info.offset); get(frame) }
                        out.write(adtsHeader(frame.size + 7))
                        out.write(frame)
                    }
                    codec.releaseOutputBuffer(outIx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        return out.toByteArray()
    }

    private fun adtsHeader(frameLen: Int): ByteArray {
        val profile = 2                       // AAC-LC
        val freqIdx = when (sampleRate) {     // ISO 14496-3 sampling_frequency_index
            48000 -> 3; 44100 -> 4; 32000 -> 5; 24000 -> 6; 22050 -> 7; 16000 -> 8; else -> 6
        }
        val chanCfg = 1                       // mono
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            (((profile - 1) shl 6) or (freqIdx shl 2) or (chanCfg shr 2)).toByte(),
            (((chanCfg and 3) shl 6) or ((frameLen shr 11) and 0x3)).toByte(),
            ((frameLen shr 3) and 0xFF).toByte(),
            (((frameLen and 0x7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }
}
