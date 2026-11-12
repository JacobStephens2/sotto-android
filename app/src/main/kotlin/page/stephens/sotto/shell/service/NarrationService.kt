package page.stephens.sotto.shell.service

// One service, one notification (10-architecture-overview.md): a Media3
// MediaSessionService that owns ExoPlayer and an attached narrator. The process
// coordinator retains a stopped writer until its durable work has finished.
// Narration streams chunk-by-chunk into the oration's ADTS file; playback starts
// as soon as the first chunk is durable; the playhead governor re-resolves the
// growing file when the player catches the synthesis frontier ("catching up" -
// the web app's Load-latest, automated). Front-loaded buffering is free here:
// synthesis always runs flat-out ahead of the playhead (the SPIKE-002 decay
// finding says bank buffer while cold).

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import page.stephens.sotto.engine.narrate.AttachedNarrator
import page.stephens.sotto.engine.narrate.NarrationCallbacks
import page.stephens.sotto.engine.narrate.NarrationCommandResult
import page.stephens.sotto.engine.narrate.NarrationCoordinator
import page.stephens.sotto.engine.store.JobLog
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.SottoDatabase
import java.io.File

class NarrationService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private lateinit var db: SottoDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var narrator: AttachedNarrator
    @Volatile private var destroyed = false
    private var playbackVersion = 0L
    private val deletedIds = mutableSetOf<String>()
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
        narrator = NarrationCoordinatorProvider.get(this).attach(NarrationCallbacks(
            onAudioReady = { row -> if (playingId == null) playSaved(row) },
            onStopPlayback = { id ->
                deletedIds.add(id)
                if (playingId == id) {
                    playbackVersion++
                    playingId = null
                    player.pause()
                    player.stop()
                    player.clearMediaItems()
                }
            },
        ))
        attachInApp()
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
            is InAppCommand.Narrate -> showResult(narrator.narrate(command.id))
            is InAppCommand.Play -> scope.launch { startPlayback(command.id) }
            // The engine sees shouldStop at the next chunk, or before it starts.
            InAppCommand.StopNarration -> showResult(narrator.stop())
            is InAppCommand.SleepTimer -> setSleepTimer(command.minutes)
            is InAppCommand.Delete -> showResult(narrator.delete(command.id))
        }
    }

    private fun showResult(result: kotlinx.coroutines.Deferred<NarrationCommandResult>) {
        scope.launch(Dispatchers.Main) {
            val text = when (val outcome = result.await()) {
                NarrationCommandResult.Missing -> "Oration no longer exists"
                NarrationCommandResult.ServerOnly -> "Download this oration before narrating"
                NarrationCommandResult.DiagnosticOnly -> "Resume this oration in Lab"
                NarrationCommandResult.Detached -> "Narration owner is no longer available"
                NarrationCommandResult.Deleting -> "This oration is being deleted"
                is NarrationCommandResult.Failed -> "Narration command failed: ${outcome.message}"
                else -> null
            }
            if (text != null && !destroyed) Toast.makeText(this@NarrationService, text, Toast.LENGTH_LONG).show()
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

    private suspend fun startPlayback(id: String) {
        val version = withContext(Dispatchers.Main) { playbackVersion }
        val row = db.orations().byId(id) ?: return
        withContext(Dispatchers.Main) {
            if (!destroyed && version == playbackVersion && id !in deletedIds) playSaved(row)
        }
    }

    private fun playSaved(row: OrationEntity) {
        if (destroyed || row.id in deletedIds || row.audioBytes <= 0 || !audioFile(row.id).exists()) return
        playbackVersion++
        playingId = row.id
        player.setMediaItem(mediaItem(row.id, row.title, audioFile(row.id)))
        player.prepare()
        if (row.playheadMs > 0) player.seekTo(row.playheadMs)
        player.play()
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
            if (ended && narrator.facts.value.activeId == id) {
                val row = db.orations().byId(id) ?: continue
                withContext(Dispatchers.Main) {
                    if (destroyed || playingId != id || id in deletedIds) return@withContext
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
        destroyed = true
        playbackVersion++
        detachInApp()
        narrator.detach()
        playingId?.let { id ->
            val pos = player.currentPosition
            val dao = db.orations()
            if (pos > 0) CoroutineScope(Dispatchers.IO).launch {
                dao.setPlayhead(id, pos, System.currentTimeMillis())
            }
        }
        session?.release(); session = null
        player.release()
        scope.cancel()
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

        const val STORAGE_FULL = NarrationCoordinator.STORAGE_FULL

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
