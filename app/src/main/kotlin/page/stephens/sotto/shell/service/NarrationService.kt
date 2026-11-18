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
import android.os.Process
import android.widget.Toast
import androidx.annotation.OptIn
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
import kotlinx.coroutines.launch
import page.stephens.sotto.engine.narrate.AttachedNarrator
import page.stephens.sotto.engine.narrate.NarrationCallbacks
import page.stephens.sotto.engine.narrate.NarrationCommandResult
import page.stephens.sotto.engine.narrate.NarrationCoordinator
import page.stephens.sotto.engine.store.SottoDatabase
import page.stephens.sotto.shell.playback.Media3PlaybackOutput
import page.stephens.sotto.shell.playback.OrationPlayback
import page.stephens.sotto.shell.playback.PlaybackPersistence
import page.stephens.sotto.shell.playback.SavedOrationPlayback
import java.io.File

class NarrationService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private lateinit var db: SottoDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var narrator: AttachedNarrator
    @Volatile private var destroyed = false
    private lateinit var playback: OrationPlayback

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            if (isAuthorizedController(controller)) super.onConnect(session, controller)
            else MediaSession.ConnectionResult.reject()
    }

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
        session = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .build()
        val coordinator = NarrationCoordinatorProvider.get(this)
        playback = SavedOrationPlayback(
            db, File(filesDir, "orations"), Media3PlaybackOutput(player),
            coordinator.facts, PlaybackPersistence.shared(db),
        )
        narrator = coordinator.attach(NarrationCallbacks(
            onAudioReady = playback::listenNow,
            onStopPlayback = playback::stopForDeletion,
        ))
        attachInApp()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (controllerInfo.uid < 0 || isAuthorizedController(controllerInfo)) session else null

    // Media3 marks controllers trusted by package name alone for notification listeners,
    // so check resolvable non-system UIDs own the claimed package before granting access.
    @OptIn(UnstableApi::class)   // ControllerInfo.isTrusted is Media3 "unstable" API
    private fun isAuthorizedController(controllerInfo: MediaSession.ControllerInfo): Boolean {
        val uid = controllerInfo.uid
        if (uid == Process.myUid()) return true
        if (!controllerInfo.isTrusted || uid < 0) return false
        if (uid != Process.SYSTEM_UID) {
            val uidPackages = packageManager.getPackagesForUid(uid)
            if (!uidPackages.isNullOrEmpty() && controllerInfo.packageName !in uidPackages) return false
        }
        return true
    }

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
            is InAppCommand.Play -> playback.play(command.id)
            // The engine sees shouldStop at the next chunk, or before it starts.
            InAppCommand.StopNarration -> showResult(narrator.stop())
            is InAppCommand.SleepTimer -> playback.sleep(command.minutes)
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

    override fun onDestroy() {
        destroyed = true
        detachInApp()
        playback.close()
        narrator.detach()
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
