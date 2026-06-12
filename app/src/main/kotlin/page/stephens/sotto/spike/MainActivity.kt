package page.stephens.sotto.spike

// SPIKE-002 (docs/03-roadmap.md Phase 0): measure on-device Kokoro synthesis.
// Differences from spike-001: a run-generation token + single-thread executor make
// overlapping runs impossible; wall time uses elapsedRealtime (counts through
// suspend) with a separate suspended-time readout; every line carries its run id;
// lifecycle events are logged; a Threads button (2/3/4) reloads the model for
// efficiency sweeps. Gate: sustained RTF >= 1.0x; target >= 1.3x.

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.BatteryManager
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class MainActivity : Activity() {

    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var btnThreads: Button
    @Volatile private var tts: OfflineTts? = null
    private val exec = Executors.newSingleThreadExecutor()   // runs can never overlap
    private val generation = AtomicInteger(0)                // STOP bumps it; stale runs exit
    @Volatile private var threads = 4
    private var lastAudio: FloatArray? = null
    private var lastRate = 24000
    private var peakPssKb = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        status = TextView(this).apply { text = "Loading model..."; textSize = 15f; setPadding(24, 24, 24, 8) }
        log = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE; setPadding(24, 8, 24, 24); setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(log) }

        val full = { b: Button ->
            b.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            b
        }
        val btnOnce = Button(this).apply { text = "Run once (~1.4k words)"; setOnClickListener { submitRun(loops = 1) } }
        val btnSustained = Button(this).apply { text = "Sustained 30 min (keep screen on!)"; setOnClickListener { submitRun(minutes = 30) } }
        val btnStop = Button(this).apply { text = "Stop"; setOnClickListener { generation.incrementAndGet(); line("stop requested") } }
        val btnPlay = Button(this).apply { text = "Play last chunk"; setOnClickListener { playLast() } }
        btnThreads = Button(this).apply { text = "Threads: $threads (tap to change)"; setOnClickListener { cycleThreads() } }
        val gate = page.stephens.sotto.shell.gate.GateRunner(this)
        val btnGate = Button(this).apply {
            text = "Phase 1 gate (narrate doc; long-press resets)"
            setOnClickListener {
                val txt = gate.gateTextOrNull()
                if (txt == null) { line("no gate text bundled (assets/gate/encyclical.md)"); return@setOnClickListener }
                val run = generation.incrementAndGet()
                line("[gate run $run] queued, threads=$threads")
                exec.submit {
                    // The gate loads its own model; free the spike's copy first
                    // so two ~1GB sessions never coexist. Threads button reloads.
                    tts?.let { it.release(); tts = null; line("spike model released for gate") }
                    try {
                        val verdict = gate.run(txt, threads, shouldStop = { generation.get() != run },
                            onStatus = { msg -> ui { status.text = "[gate] $msg · thermal ${thermal()} · batt ${batteryPct()}%" } })
                        line("[gate run $run] $verdict"); android.util.Log.i("SPIKE", verdict)
                    } catch (t: Throwable) { line("[gate run $run] ERROR: $t") }
                }
            }
            setOnLongClickListener { generation.incrementAndGet(); exec.submit { gate.reset(); line("gate reset") }; true }
        }
        val btnKill = Button(this).apply {
            text = "Kill process (test resume)"
            setOnClickListener { android.os.Process.killProcess(android.os.Process.myPid()) }
        }
        val btnPlayGate = Button(this).apply {
            text = "Play gate audio"
            setOnClickListener {
                if (!gate.audioFile.exists()) { line("no gate audio yet"); return@setOnClickListener }
                runCatching {
                    android.media.MediaPlayer().apply {
                        setDataSource(gate.audioFile.absolutePath); prepare(); start()
                        line("playing gate audio (${gate.audioFile.length() / 1048576}MB)")
                    }
                }.onFailure { line("play failed: $it") }
            }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 0)
            addView(full(btnOnce)); addView(full(btnSustained)); addView(full(btnStop))
            addView(full(btnPlay)); addView(full(btnThreads))
            addView(full(btnGate)); addView(full(btnKill)); addView(full(btnPlayGate))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status); addView(buttons)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })

        exec.submit { loadModel() }
    }

    override fun onPause() { super.onPause(); line("lifecycle: onPause @${SystemClock.elapsedRealtime() / 1000}s") }
    override fun onResume() { super.onResume(); if (::log.isInitialized) line("lifecycle: onResume @${SystemClock.elapsedRealtime() / 1000}s") }

    private fun loadModel() {
        ui { status.text = "Loading model (threads=$threads)..." }
        tts?.let { it.release(); tts = null }
        val t0 = SystemClock.elapsedRealtime()
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
                numThreads = threads,
                provider = "cpu",
            ),
        )
        tts = OfflineTts(assets, cfg)
        val loadS = (SystemClock.elapsedRealtime() - t0) / 1000.0
        line("model-load %.1fs (threads=$threads)".format(loadS))
        ui { status.text = "Model loaded in %.1fs · threads=$threads. Ready.".format(loadS) }
    }

    private fun cycleThreads() {
        threads = when (threads) { 4 -> 2; 2 -> 3; else -> 4 }
        ui { btnThreads.text = "Threads: $threads (tap to change)" }
        generation.incrementAndGet()   // end any active run before reloading
        exec.submit { loadModel() }
    }

    private fun submitRun(loops: Int = Int.MAX_VALUE, minutes: Int = 0) {
        val run = generation.incrementAndGet()
        line("[run $run] queued (${if (minutes > 0) "${minutes}min sustained" else "$loops loop(s)"}, threads=$threads)")
        exec.submit { doRun(run, loops, minutes) }
    }

    private fun doRun(run: Int, loops: Int, minutes: Int) {
        val engine = tts ?: return line("[run $run] model not loaded")
        try {
            val chunks = chunk(TEXT)
            val words = TEXT.trim().split(Regex("\\s+")).size
            line("[run $run] start: $words words/loop, ${chunks.size} chunks")
            val battery0 = batteryPct()
            // elapsedRealtime counts through deep sleep; uptimeMillis does not.
            // Their drift = time the device spent suspended during the run.
            val wall0 = SystemClock.elapsedRealtime()
            val up0 = SystemClock.uptimeMillis()
            val deadline = wall0 + minutes * 60_000L
            var synthMs = 0L; var audioSecs = 0.0; var nChunks = 0; var loop = 0
            outer@ while (generation.get() == run && loop < loops &&
                (minutes == 0 || SystemClock.elapsedRealtime() < deadline)) {
                for (c in chunks) {
                    if (generation.get() != run || (minutes > 0 && SystemClock.elapsedRealtime() > deadline)) break@outer
                    val t0 = SystemClock.elapsedRealtime()
                    val audio = engine.generate(text = c, sid = SID, speed = 1.0f)
                    val dt = SystemClock.elapsedRealtime() - t0
                    synthMs += dt; nChunks++
                    lastAudio = audio.samples; lastRate = audio.sampleRate
                    val secs = audio.samples.size.toDouble() / audio.sampleRate
                    audioSecs += secs
                    samplePss()
                    val rtfCum = audioSecs / (synthMs / 1000.0)
                    ui {
                        status.text = ("[run $run] chunk $nChunks · inst %.2fx · cum %.2fx · %.0fs audio · " +
                                "thermal ${thermal()} · batt ${batteryPct()}%% · peakPSS %dMB")
                            .format(secs / (dt / 1000.0), rtfCum, audioSecs, peakPssKb / 1024)
                    }
                    if (nChunks % 10 == 0) line(
                        "[run $run] n=%d cumRTF=%.2fx audio=%.0fs wall=%.0fs batt=%d%% thermal=%s pssMB=%d"
                            .format(nChunks, rtfCum, audioSecs,
                                (SystemClock.elapsedRealtime() - wall0) / 1000.0,
                                batteryPct(), thermal(), peakPssKb / 1024))
                }
                loop++
            }
            val wallS = (SystemClock.elapsedRealtime() - wall0) / 1000.0
            val suspendedS = wallS - (SystemClock.uptimeMillis() - up0) / 1000.0
            val battery1 = batteryPct()
            val why = when {
                generation.get() != run -> "stopped/superseded"
                minutes > 0 && SystemClock.elapsedRealtime() >= deadline -> "deadline reached"
                else -> "loops done"
            }
            val summary = """
                |== SPIKE-002 SUMMARY [run $run] ($why) ==
                |chunks=$nChunks  audio=%.0fs  synth=%.0fs  wall=%.0fs  suspended=%.0fs
                |RTF (synth basis) = %.2fx   RTF (wall basis) = %.2fx
                |battery $battery0%% -> $battery1%% (Δ${battery0 - battery1} over %.1f min => %.1f%%/hr)
                |thermal-final=${thermal()}  peakPSS=${peakPssKb / 1024}MB
                |threads=$threads sid=$SID abi=arm64-v8a
            """.trimMargin().format(audioSecs, synthMs / 1000.0, wallS, suspendedS,
                audioSecs / (synthMs / 1000.0), audioSecs / wallS,
                wallS / 60.0, (battery0 - battery1) / (wallS / 3600.0))
            line(summary)
            Log.i("SPIKE", summary)
        } catch (t: Throwable) {
            line("[run $run] ERROR: ${Log.getStackTraceString(t).take(800)}")
            Log.e("SPIKE", "run $run failed", t)
        }
    }

    private fun playLast() {
        val samples = lastAudio ?: return line("nothing synthesized yet")
        Thread {
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
        }.start()
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
        private const val SID = 0   // voice identity is irrelevant to RTF
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
        }
    }
}
