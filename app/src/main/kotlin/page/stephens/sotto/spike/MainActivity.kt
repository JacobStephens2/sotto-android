package page.stephens.sotto.spike

// SPIKE-001 (docs/03-roadmap.md Phase 0): measure on-device Kokoro synthesis.
// Throwaway code - the only deliverables are the numbers for docs/_state.md:
// sustained RTF, battery %/hr, thermal status over 30 min, peak PSS, APK size.
// Gate: sustained RTF >= 1.0x; target >= 1.3x.

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.BatteryManager
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max

class MainActivity : Activity() {

    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private var tts: OfflineTts? = null
    private val running = AtomicBoolean(false)
    private var lastAudio: FloatArray? = null
    private var lastRate = 24000
    private var peakPssKb = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        status = TextView(this).apply { text = "Loading model..."; textSize = 16f; setPadding(24, 24, 24, 8) }
        log = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE; setPadding(24, 8, 24, 24) }
        scroll = ScrollView(this).apply { addView(log) }

        val btnOnce = Button(this).apply { text = "Run once (~2k words)"; setOnClickListener { startRun(loops = 1) } }
        val btnSustained = Button(this).apply { text = "Sustained 30 min"; setOnClickListener { startRun(minutes = 30) } }
        val btnStop = Button(this).apply { text = "Stop"; setOnClickListener { running.set(false) } }
        val btnPlay = Button(this).apply { text = "Play last chunk"; setOnClickListener { playLast() } }
        // Vertical, full-width: a horizontal row overflowed the screen on device.
        val full = { b: Button ->
            b.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            b
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 0)
            addView(full(btnOnce)); addView(full(btnSustained)); addView(full(btnStop)); addView(full(btnPlay))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status); addView(buttons)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })

        thread {
            val t0 = System.nanoTime()
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = "models/model.int8.onnx",
                        voices = "models/voices.bin",
                        tokens = "models/tokens.txt",
                        dataDir = copyAssetDir("models/espeak-ng-data"),
                        lexicon = "models/lexicon-us-en.txt,models/lexicon-zh.txt",
                        dictDir = copyAssetDir("models/dict"),
                    ),
                    numThreads = NUM_THREADS,
                    provider = "cpu",
                ),
            )
            tts = OfflineTts(assets, cfg)
            val loadS = (System.nanoTime() - t0) / 1e9
            ui { status.text = "Model loaded in %.1fs · threads=$NUM_THREADS · sid=$SID. Ready.".format(loadS) }
            line("model-load %.1fs".format(loadS))
        }
    }

    private fun startRun(loops: Int = Int.MAX_VALUE, minutes: Int = 0) {
        val engine = tts ?: return line("model not loaded yet")
        if (!running.compareAndSet(false, true)) return line("already running")
        thread {
            val chunks = chunk(TEXT)
            val words = TEXT.trim().split(Regex("\\s+")).size
            line("start: $words words/loop, ${chunks.size} chunks, deadline=${if (minutes > 0) "${minutes}min" else "$loops loop(s)"}")
            val battery0 = batteryPct()
            val wallStart = System.nanoTime()
            val deadline = wallStart + minutes * 60_000_000_000L
            var synthNs = 0L; var audioSecs = 0.0; var nChunks = 0; var loop = 0
            outer@ while (running.get() && loop < loops && (minutes == 0 || System.nanoTime() < deadline)) {
                for (c in chunks) {
                    if (!running.get() || (minutes > 0 && System.nanoTime() > deadline)) break@outer
                    val t0 = System.nanoTime()
                    val audio = engine.generate(text = c, sid = SID, speed = 1.0f)
                    val dt = System.nanoTime() - t0
                    synthNs += dt; nChunks++
                    lastAudio = audio.samples; lastRate = audio.sampleRate
                    val secs = audio.samples.size.toDouble() / audio.sampleRate
                    audioSecs += secs
                    samplePss()
                    val rtfInst = secs / (dt / 1e9)
                    val rtfCum = audioSecs / (synthNs / 1e9)
                    ui {
                        status.text = ("chunk $nChunks · inst %.2fx · cum %.2fx · %.0fs audio · " +
                                "thermal ${thermal()} · batt ${batteryPct()}%% · peakPSS %dMB")
                            .format(rtfInst, rtfCum, audioSecs, peakPssKb / 1024)
                    }
                    if (nChunks % 10 == 0) line(
                        "n=%d cumRTF=%.2fx audio=%.0fs wall=%.0fs batt=%d%% thermal=%s pssMB=%d"
                            .format(nChunks, rtfCum, audioSecs, (System.nanoTime() - wallStart) / 1e9,
                                batteryPct(), thermal(), peakPssKb / 1024))
                }
                loop++
            }
            val wallS = (System.nanoTime() - wallStart) / 1e9
            val battery1 = batteryPct()
            val summary = """
                |== SPIKE-001 SUMMARY ==
                |chunks=$nChunks  audio=%.0fs  synth=%.0fs  wall=%.0fs
                |RTF (synth-time basis)   = %.2fx
                |RTF (wall-clock basis)   = %.2fx
                |battery $battery0%% -> $battery1%% (Δ${battery0 - battery1} over %.1f min => %.1f%%/hr)
                |thermal-final=${thermal()}  peakPSS=${peakPssKb / 1024}MB
                |threads=$NUM_THREADS sid=$SID abi=arm64-v8a
            """.trimMargin().format(audioSecs, synthNs / 1e9, wallS,
                audioSecs / (synthNs / 1e9), audioSecs / wallS,
                wallS / 60.0, (battery0 - battery1) / (wallS / 3600.0))
            line(summary)
            Log.i("SPIKE", summary)
            running.set(false)
        }
    }

    private fun playLast() {
        val samples = lastAudio ?: return line("nothing synthesized yet")
        thread {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(lastRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 4)
                .build()
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
        }
    }

    // --- helpers ---------------------------------------------------------------

    /** espeak-ng-data and dict must be real files on disk, not assets. */
    private fun copyAssetDir(path: String): String {
        val out = java.io.File(getExternalFilesDir(null) ?: filesDir, path)
        if (out.exists()) return out.absolutePath
        fun copy(p: String, dst: java.io.File) {
            val children = assets.list(p) ?: emptyArray()
            if (children.isEmpty()) {
                dst.parentFile?.mkdirs()
                assets.open(p).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            } else children.forEach { copy("$p/$it", java.io.File(dst, it)) }
        }
        copy(path, out)
        return out.absolutePath
    }

    private fun chunk(text: String, limit: Int = 1000): List<String> {
        // Spike-grade chunking (the real ported pipeline arrives in Phase 1).
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        val out = ArrayList<String>(); val sb = StringBuilder()
        for (s in sentences) {
            if (sb.length + s.length > limit && sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun batteryPct(): Int =
        (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun thermal(): String {
        val st = (getSystemService(POWER_SERVICE) as PowerManager).currentThermalStatus
        return arrayOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")
            .getOrElse(st) { "?$st" }
    }

    private fun samplePss() {
        val mi = Debug.MemoryInfo(); Debug.getMemoryInfo(mi)
        peakPssKb = max(peakPssKb, mi.totalPss.toLong())
    }

    private fun line(s: String) = ui {
        log.append(s + "\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun ui(f: () -> Unit) = runOnUiThread(f)

    companion object {
        private const val NUM_THREADS = 4   // big.LITTLE: experiment with 2-4
        private const val SID = 0           // voice identity is irrelevant to RTF
        private val TEXT = buildString {
            repeat(8) { i ->
                append("Section $i. ")
                append(
                    "Humanity, created with all its grandeur, is today facing a pivotal choice, " +
                    "and each generation inherits the task of shaping its own era. " +
                    "The reader of long documents knows the problem well: a dense plan or a " +
                    "carefully argued specification is easier to absorb on a walk than at a desk. " +
                    "This application narrates such documents entirely on the device, so that no " +
                    "text and no audio ever leaves the phone, and the manifest itself proves the claim. " +
                    "A measured number beats an estimated one, which is why this spike exists at all. " +
                    "Synthesis must stay ahead of the listener; if it cannot, the premise fails and " +
                    "we will know it before writing the real application rather than after. " +
                    "Quiet voices carry surprisingly far when the room itself is quiet, and a " +
                    "private narrator needs no network to be heard clearly by its only listener. " +
                    "Twenty-six letters, arranged well, are sufficient for nearly everything; " +
                    "the rest is patience, revision, and an honest account of what was measured. "
                )
            }
        } // ~2,050 words after the 8 repeats
    }
}
