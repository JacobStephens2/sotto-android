package page.stephens.sotto.shell.service

// One service, one notification (10-architecture-overview.md): a Media3
// MediaSessionService that owns both the ExoPlayer and the narration coroutine.
// Narration streams chunk-by-chunk into the oration's ADTS file; playback starts
// as soon as the first chunk is durable; the playhead governor re-resolves the
// growing file when the player catches the synthesis frontier ("catching up" -
// the web app's Load-latest, automated). Front-loaded buffering is free here:
// synthesis always runs flat-out ahead of the playhead (the SPIKE-002 decay
// finding says bank buffer while cold).

import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import page.stephens.sotto.engine.narrate.NarrationEngine
import page.stephens.sotto.engine.narrate.NarrationResult
import page.stephens.sotto.engine.store.FileAudioSink
import page.stephens.sotto.engine.store.JobLog
import page.stephens.sotto.engine.store.RoomProgressStore
import page.stephens.sotto.engine.store.SottoDatabase
import page.stephens.sotto.engine.text.TextPipeline
import page.stephens.sotto.shell.synth.AdtsAacEncoder
import page.stephens.sotto.shell.synth.SherpaSynthesizer
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class NarrationService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private lateinit var db: SottoDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var synth: SherpaSynthesizer? = null
    private val narratingId = AtomicReference<String?>(null)
    @Volatile private var playingId: String? = null

    @OptIn(UnstableApi::class)   // ADTS constant-bitrate seeking is Media3 "unstable" API
    override fun onCreate() {
        super.onCreate()
        db = SottoDatabase.open(this)
        // ADTS has no seek index; constant-bitrate seeking makes seekTo work.
        val extractors = DefaultExtractorsFactory()
            .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractors))
            .setHandleAudioBecomingNoisy(true)
            .build()
        session = MediaSession.Builder(this, player).build()
        attachInApp()
        scope.launch {
            db.orations().requeueInterrupted(System.currentTimeMillis())
            pickupQueue()   // PRD S4: interrupted/queued narration resumes on its own
        }
        scope.launch { governor() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Exported for the Media3 session, so any app can start this service.
        // Narrate, play, stop, sleep timer, and delete are ignored here;
        // InAppNarrationService accepts them in-process (ADR-008). super still
        // delivers media-button and session intents.
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    private fun performInApp(command: InAppCommand) {
        when (command) {
            is InAppCommand.Narrate -> startNarration(command.id)
            is InAppCommand.Play -> scope.launch { startPlayback(command.id) }
            // The engine sees shouldStop at the next chunk, or before it starts.
            InAppCommand.StopNarration -> narratingId.set(null)
            is InAppCommand.SleepTimer -> setSleepTimer(command.minutes)
            is InAppCommand.Delete -> scope.launch { deleteOration(command.id) }
        }
    }

    private fun startNarration(id: String) {
        if (!narratingId.compareAndSet(null, id)) {
            if (narratingId.get() != id) scope.launch {
                JobLog(db.joblog()).log("queued", db.orations().byId(id)?.title ?: id, id, "waiting for the current narration")
            }
            return
        }
        // Posted, not run inline, so a stop delivered next on this thread wins
        // before the model loads.
        scope.launch(Dispatchers.Main) {
            if (narratingId.get() != id) {
                narratingId.compareAndSet(id, null)
                withContext(Dispatchers.Default) { stopBeforeStart(id) }
                return@launch
            }
            withContext(Dispatchers.Default) { runNarration(id) }
        }
        // Listen-now: start playback as soon as the first chunk is durable.
        scope.launch {
            val f = audioFile(id)
            while (narratingId.get() == id && f.length() == 0L) delay(250)
            if (f.length() > 0L && playingId == null) startPlayback(id)
        }
    }

    private suspend fun stopBeforeStart(id: String) {
        val row = db.orations().byId(id)
        if (row != null && row.status != "COMPLETE") {
            db.orations().setStatus(id, "STOPPED", System.currentTimeMillis())
            JobLog(db.joblog()).log("stopped", row.title, id, "before the first chunk")
        }
        pickupQueue()
    }

    private suspend fun runNarration(id: String) {
        val dao = db.orations()
        val log = JobLog(db.joblog())
        val row = dao.byId(id) ?: run { narratingId.set(null); return }
        try {
            val text = File(filesDir, "orations/$id/source.md").readText()
            val cleaned = TextPipeline.cleanMarkdown(text)
            dao.setStatus(id, "NARRATING", System.currentTimeMillis())
            log.log(if (row.chunksDone > 0) "resumed" else "started", row.title, id,
                if (row.chunksDone > 0) "from chunk ${row.chunksDone}" else "")
            val s = synth ?: SherpaSynthesizer(this@NarrationService, numThreads = 4).also { synth = it }
            var totalSet = row.chunksTotal != null
            val result = FileAudioSink(audioFile(id)).use { sink ->
                NarrationEngine(s, AdtsAacEncoder(s.sampleRate), RoomProgressStore(dao))
                    .narrate(id, cleaned, row.voiceId, sink,
                        shouldStop = { narratingId.get() != id },
                        onProgress = { _, t ->
                            if (!totalSet) { dao.setChunksTotal(id, t, System.currentTimeMillis()); totalSet = true }
                        })
            }
            when (result) {
                is NarrationResult.Complete -> {
                    dao.setStatus(id, "COMPLETE", System.currentTimeMillis())
                    log.log("completed", row.title, id, "${result.chunks}ch ${result.audioBytes}B")
                }
                is NarrationResult.Stopped -> {
                    dao.setStatus(id, "STOPPED", System.currentTimeMillis())
                    log.log("stopped", row.title, id, "${result.chunksDone}/${result.totalChunks}")
                }
                is NarrationResult.StorageFull -> {
                    // PRD edge case: stop gracefully, keep the partial, say why.
                    dao.setStatus(id, "STOPPED", System.currentTimeMillis(), STORAGE_FULL)
                    log.log("stopped", row.title, id, "storage full at ${result.chunksDone}/${result.totalChunks}")
                }
                is NarrationResult.Failed -> {
                    dao.setStatus(id, "ERROR", System.currentTimeMillis(), result.cause.message)
                    log.log("error", row.title, id, result.cause.toString().take(160))
                }
            }
        } catch (t: Throwable) {
            dao.setStatus(id, "ERROR", System.currentTimeMillis(), t.message)
            log.log("error", row.title, id, t.toString().take(160))
        } finally {
            narratingId.compareAndSet(id, null)
            pickupQueue()   // FIFO: queued orations narrate one after another
        }
    }

    private fun pickupQueue() {
        if (narratingId.get() != null) return
        scope.launch {
            db.orations().nextQueued()?.let { startNarration(it.id) }
        }
    }

    private var sleepJob: kotlinx.coroutines.Job? = null
    private var sleepToken = 0
    private fun setSleepTimer(minutes: Int) {
        // A newer request invalidates a timer whose delayed resume still arrives.
        val token = ++sleepToken
        sleepJob?.cancel(); sleepJob = null
        if (minutes <= 0) return
        // The wait does not block the thread. Pausing has to happen on the
        // player thread, so the timer waits there and the log write hops off it.
        sleepJob = scope.launch(Dispatchers.Main) {
            delay(minutes * 60_000L)
            if (token != sleepToken) return@launch
            player.pause()
            withContext(Dispatchers.Default) {
                JobLog(db.joblog()).log("sleep-timer", "paused after $minutes min")
            }
        }
    }

    /** Deletion is real deletion (constitution boundary 7) and lives here so the
     *  one process owner can stop narration/playback before removing files. */
    private suspend fun deleteOration(id: String) {
        if (narratingId.get() == id) narratingId.set(null)
        withContext(Dispatchers.Main) {
            if (playingId == id) { playingId = null; player.stop(); player.clearMediaItems() }
        }
        val row = db.orations().byId(id)
        db.orations().delete(id)
        File(filesDir, "orations/$id").deleteRecursively()
        JobLog(db.joblog()).log("deleted", row?.title ?: id, id)
    }

    private suspend fun startPlayback(id: String) {
        val row = db.orations().byId(id) ?: return
        val f = audioFile(id)
        if (!f.exists() || f.length() == 0L) return
        withContext(Dispatchers.Main) {
            playingId = id
            player.setMediaItem(mediaItem(id, row.title, f))
            player.prepare()
            if (row.playheadMs > 0) player.seekTo(row.playheadMs)
            player.play()
        }
    }

    /** Every 2s: persist the playhead; if the player hit the end of a still-growing
     *  file, re-resolve it and continue from the same position ("catching up"). */
    private suspend fun governor() {
        while (true) {
            delay(2000)
            val id = playingId ?: continue
            val (pos, ended) = withContext(Dispatchers.Main) {
                player.currentPosition to (player.playbackState == Player.STATE_ENDED)
            }
            if (pos > 0) db.orations().setPlayhead(id, pos, System.currentTimeMillis())
            if (ended && narratingId.get() == id) {
                val row = db.orations().byId(id) ?: continue
                withContext(Dispatchers.Main) {
                    player.setMediaItem(mediaItem(id, row.title, audioFile(id)))
                    player.prepare()
                    player.seekTo(pos)
                    player.play()
                }
            }
        }
    }

    private fun mediaItem(id: String, title: String, f: File): MediaItem =
        MediaItem.Builder()
            .setUri(Uri.fromFile(f))
            .setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("Sotto").build())
            .build()

    private fun audioFile(id: String) = File(filesDir, "orations/$id/audio.aac")

    override fun onDestroy() {
        detachInApp()
        playingId?.let { id ->
            val pos = player.currentPosition
            if (pos > 0) runCatching { db.orations().setPlayhead(id, pos, System.currentTimeMillis()) }
        }
        narratingId.set(null)
        session?.release(); session = null
        player.release()
        scope.cancel()
        synth?.close(); synth = null
        super.onDestroy()
    }

    private fun attachInApp() {
        synchronized(inAppGate) {
            running = this
            while (pendingInApp.isNotEmpty()) performInApp(pendingInApp.removeFirst())
        }
    }

    private fun detachInApp() {
        synchronized(inAppGate) {
            if (running === this) running = null
        }
    }

    companion object {
        private val inAppGate = Any()
        private var running: NarrationService? = null
        private val pendingInApp = ArrayDeque<InAppCommand>()

        const val STORAGE_FULL = "Storage full - free some space, then tap to resume"

        /** Same-process only. [InAppNarrationService] is the caller; an exported
         *  start cannot reach this, because the command is not on the intent. */
        internal fun acceptInApp(command: InAppCommand) {
            synchronized(inAppGate) {
                val service = running
                if (service != null) service.performInApp(command)
                else pendingInApp.addLast(command)
            }
        }

        /** Tests share a process, and so does this queue. */
        internal fun clearInAppForTest() {
            synchronized(inAppGate) {
                running = null
                pendingInApp.clear()
            }
        }
    }
}
