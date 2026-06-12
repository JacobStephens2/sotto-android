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
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
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
        scope.launch { db.orations().requeueInterrupted(System.currentTimeMillis()) }
        scope.launch { governor() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_NARRATE -> intent.getStringExtra(EXTRA_ID)?.let { startNarration(it) }
            ACTION_PLAY -> intent.getStringExtra(EXTRA_ID)?.let { id -> scope.launch { startPlayback(id) } }
            ACTION_STOP_NARRATION -> narratingId.set(null)   // engine sees shouldStop at next chunk
        }
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    private fun startNarration(id: String) {
        if (!narratingId.compareAndSet(null, id)) {
            if (narratingId.get() != id) scope.launch {
                JobLog(db.joblog()).log("error", "narration busy", id, "single worker; queueing is Phase 3")
            }
            return
        }
        scope.launch {
            val dao = db.orations()
            val log = JobLog(db.joblog())
            val row = dao.byId(id) ?: run { narratingId.set(null); return@launch }
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
            }
        }
        // Listen-now: start playback as soon as the first chunk is durable.
        scope.launch {
            val f = audioFile(id)
            while (narratingId.get() == id && f.length() == 0L) delay(250)
            if (f.length() > 0L && playingId == null) startPlayback(id)
        }
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

    companion object {
        const val ACTION_NARRATE = "page.stephens.sotto.NARRATE"
        const val ACTION_PLAY = "page.stephens.sotto.PLAY"
        const val ACTION_STOP_NARRATION = "page.stephens.sotto.STOP_NARRATION"
        const val EXTRA_ID = "orationId"
    }
}
