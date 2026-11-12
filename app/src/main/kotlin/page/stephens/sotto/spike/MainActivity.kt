package page.stephens.sotto.spike

// Lab is a lifecycle caller of the retained narration worker.

import android.app.Activity
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import page.stephens.sotto.engine.narrate.AttachedDiagnostics
import page.stephens.sotto.engine.narrate.DiagnosticEvent
import page.stephens.sotto.engine.narrate.DiagnosticRequest
import page.stephens.sotto.engine.narrate.DiagnosticResult
import page.stephens.sotto.shell.service.InAppNarrationService
import page.stephens.sotto.shell.service.NarrationCoordinatorProvider
import java.io.File

class MainActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var btnThreads: Button
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lab: AttachedDiagnostics? = null
    private var threads = 4
    private var lastAudio: DiagnosticEvent.Audio? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply { text = "Lab ready"; textSize = 15f; setPadding(24, 24, 24, 8) }
        log = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE; setPadding(24, 8, 24, 24); setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(log) }
        val full = { button: Button ->
            button.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            button
        }
        val once = Button(this).apply { text = "Run once (~1.4k words)"; setOnClickListener { request(DiagnosticRequest.Measure(TEXT, loops = 1)) } }
        val sustained = Button(this).apply { text = "Sustained 30 min (keep screen on!)"; setOnClickListener { request(DiagnosticRequest.Measure(TEXT, minutes = 30)) } }
        val stop = Button(this).apply { text = "Stop"; setOnClickListener { request(DiagnosticRequest.Stop) } }
        val play = Button(this).apply { text = "Play last chunk"; setOnClickListener { playLast() } }
        btnThreads = Button(this).apply {
            text = "Threads: $threads (tap to change)"
            setOnClickListener { request(DiagnosticRequest.Threads(when (threads) { 4 -> 2; 2 -> 3; else -> 4 })) }
        }
        val gate = Button(this).apply {
            text = "Phase 1 gate (resume; long-press resets)"
            setOnClickListener {
                val text = runCatching { assets.open("gate/encyclical.md").bufferedReader().use { it.readText() } }.getOrNull()
                if (text == null) line("no gate text bundled (assets/gate/encyclical.md)")
                else request(DiagnosticRequest.Gate(text))
            }
            setOnLongClickListener { request(DiagnosticRequest.ResetGate); true }
        }
        val kill = Button(this).apply {
            text = "Kill process (test resume)"
            setOnClickListener { android.os.Process.killProcess(android.os.Process.myPid()) }
        }
        val playGate = Button(this).apply {
            text = "Play gate audio"
            setOnClickListener {
                val audio = File(filesDir, "orations/gate/audio.aac")
                if (!audio.exists()) line("no gate audio yet")
                else runCatching {
                    startService(Intent(this@MainActivity, InAppNarrationService::class.java)
                        .setAction(InAppNarrationService.ACTION_PLAY)
                        .putExtra(InAppNarrationService.EXTRA_ID, "gate"))
                    line("playing gate audio (${audio.length() / 1048576}MB)")
                }.onFailure { line("play failed: $it") }
            }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 0)
            listOf(once, sustained, stop, play, btnThreads, gate, kill, playGate).forEach { addView(full(it)) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status); addView(buttons)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    override fun onStart() {
        super.onStart()
        lab = NarrationCoordinatorProvider.get(this).attachDiagnostics { event ->
            when (event) {
                is DiagnosticEvent.Status -> status.text = event.text
                is DiagnosticEvent.Line -> { line(event.text); Log.i("SPIKE", event.text) }
                is DiagnosticEvent.Audio -> lastAudio = event
                is DiagnosticEvent.Measured -> Unit
            }
        }
        request(DiagnosticRequest.Threads(threads))
    }

    override fun onStop() {
        lab?.detach()
        lab = null
        super.onStop()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onPause() { super.onPause(); line("lifecycle: onPause @${SystemClock.elapsedRealtime() / 1000}s") }
    override fun onResume() { super.onResume(); line("lifecycle: onResume @${SystemClock.elapsedRealtime() / 1000}s") }

    private fun request(request: DiagnosticRequest) {
        val target = lab ?: return
        scope.launch {
            val result = target.request(request).await()
            if (lab !== target) return@launch
            val message = when (result) {
                DiagnosticResult.Busy -> "Busy - ordinary narration is active or queued"
                DiagnosticResult.Accepted -> "Diagnostic queued"
                DiagnosticResult.StopRequested -> "Stop requested - waiting for the current chunk"
                DiagnosticResult.Configured -> {
                    if (request is DiagnosticRequest.Threads) {
                        threads = request.count
                        btnThreads.text = "Threads: $threads (tap to change)"
                    }
                    "Threads: $threads configured"
                }
                DiagnosticResult.Reset -> "gate reset"
                DiagnosticResult.Detached -> "Lab is no longer attached"
                DiagnosticResult.Superseded -> "Diagnostic superseded"
                is DiagnosticResult.Failed -> "Diagnostic failed: ${result.message}"
            }
            status.text = message
            line(message)
        }
    }

    private fun playLast() {
        val audio = lastAudio ?: return line("nothing narrated yet")
        Thread {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(audio.sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(audio.samples.size * 4)
                .build()
            track.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
        }.start()
    }

    private fun line(text: String) {
        log.append(text + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    companion object {
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
