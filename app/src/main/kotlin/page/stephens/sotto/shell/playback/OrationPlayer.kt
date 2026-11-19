package page.stephens.sotto.shell.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.SottoDatabase
import page.stephens.sotto.shell.service.InAppNarrationService
import page.stephens.sotto.shell.service.NarrationService
import java.io.File

internal sealed interface OrationPlayerState {
    data object Loading : OrationPlayerState
    data object Missing : OrationPlayerState
    data class Ready(
        val id: String,
        val title: String,
        val status: String,
        val words: Int,
        val chunksDone: Int,
        val chunksTotal: Int?,
        val errorMessage: String?,
        val downloaded: Boolean,
        val audioBytes: Long,
        val isLoaded: Boolean,
        val isPlaying: Boolean,
        val catchingUp: Boolean,
        val speed: Float,
        val sleepMinutes: Int?,
        val sourceText: String,
    ) : OrationPlayerState {
        val canPlay: Boolean get() = downloaded && audioBytes > 0L
    }
}

internal data class PlaybackTimeline(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

internal interface OrationPlayer {
    val state: StateFlow<OrationPlayerState>
    val timeline: StateFlow<PlaybackTimeline>

    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun skipBack()
    fun skipForward()
    fun setSpeed(speed: Float)
    fun toggleSleepTimer(minutes: Int)
    fun stopNarration()
    fun close()

    companion object {
        @Volatile private var factoryForTest: ((String) -> OrationPlayer)? = null

        fun setFactoryForTest(factory: ((String) -> OrationPlayer)?) {
            factoryForTest = factory
        }

        fun create(context: Context, id: String): OrationPlayer {
            factoryForTest?.invoke(id)?.let { return it }
            val app = context.applicationContext
            val db = SottoDatabase.open(app)
            return SavedOrationPlayer(
                id = id,
                db = db,
                root = File(app.filesDir, "orations"),
                persistence = PlaybackPersistence.shared(db),
                transport = Media3PlayerTransport(app),
                commands = InAppPlayerCommands(app),
            )
        }
    }
}

internal data class TransportSnapshot(
    val connected: Boolean = false,
    val mediaId: String? = null,
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val speed: Float = 1.0f,
    val positionMs: Long = 0L,
    val durationMs: Long = C.TIME_UNSET,
)

internal interface PlayerTransport {
    interface Listener {
        fun onConnected(snapshot: TransportSnapshot, sleepMinutes: Int?)
        fun onPlayerChanged(snapshot: TransportSnapshot)
        fun onExtrasChanged(sleepMinutes: Int?)
        fun onDisconnected()
    }

    fun connect(listener: Listener)
    fun currentPositionMs(): Long
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekBack()
    fun seekForward()
    fun setPlaybackSpeed(speed: Float)
    fun close()
}

internal interface PlayerCommands {
    fun play(id: String)
    fun sleepTimer(minutes: Int)
    fun stopNarration()
}

internal class SavedOrationPlayer(
    private val id: String,
    private val db: SottoDatabase,
    private val root: File,
    private val persistence: PlaybackPersistence,
    private val transport: PlayerTransport,
    private val commands: PlayerCommands,
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val playerDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val clockDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : OrationPlayer {
    private val scope = CoroutineScope(SupervisorJob() + playerDispatcher)
    private val _state = MutableStateFlow<OrationPlayerState>(OrationPlayerState.Loading)
    override val state: StateFlow<OrationPlayerState> = _state.asStateFlow()

    private val _timeline = MutableStateFlow(PlaybackTimeline())
    override val timeline: StateFlow<PlaybackTimeline> = _timeline.asStateFlow()

    private var closed = false
    private var currentRow: OrationEntity? = null
    private var transportState = TransportSnapshot()
    private var sleepMinutes: Int? = null
    private var sourceText = ""
    private var sourceLoadedDownloaded: Boolean? = null
    private var sourceLoadJob: Job? = null
    private var tickerJob: Job? = null

    init {
        scope.launch {
            db.orations().byIdFlow(id).collect { row ->
                onRow(row)
            }
        }
        transport.connect(object : PlayerTransport.Listener {
            override fun onConnected(snapshot: TransportSnapshot, sleepMinutes: Int?) {
                if (closed) return
                transportState = snapshot.copy(connected = true)
                this@SavedOrationPlayer.sleepMinutes = sleepMinutes
                publishState()
            }

            override fun onPlayerChanged(snapshot: TransportSnapshot) {
                if (closed) return
                transportState = snapshot.copy(connected = true)
                publishState()
            }

            override fun onExtrasChanged(sleepMinutes: Int?) {
                if (closed) return
                this@SavedOrationPlayer.sleepMinutes = sleepMinutes
                publishState()
            }

            override fun onDisconnected() {
                if (closed) return
                transportState = TransportSnapshot()
                sleepMinutes = null
                publishState()
            }
        })
    }

    private fun onRow(row: OrationEntity?) {
        if (closed) return
        if (row == null) {
            currentRow = null
            sourceLoadedDownloaded = null
            sourceText = ""
            sourceLoadJob?.cancel()
            sourceLoadJob = null
            tickerJob?.cancel()
            tickerJob = null
            _state.value = OrationPlayerState.Missing
            _timeline.value = PlaybackTimeline()
            return
        }
        currentRow = row
        if (sourceLoadedDownloaded == null || sourceLoadedDownloaded != row.downloaded) {
            sourceLoadedDownloaded = row.downloaded
            sourceLoadJob?.cancel()
            val expectedDownloaded = row.downloaded
            sourceLoadJob = scope.launch {
                val loaded = withContext(storageDispatcher) {
                    runCatching { File(root, "$id/source.md").readText() }.getOrDefault("")
                }
                if (closed || currentRow == null || sourceLoadedDownloaded != expectedDownloaded) return@launch
                sourceText = loaded
                publishState()
            }
        }
        publishState()
    }

    private fun publishState() {
        if (closed) return
        val row = currentRow ?: return
        val loaded = transportState.connected && transportState.mediaId == id
        val playing = loaded && transportState.isPlaying
        val ended = loaded && transportState.playbackState == Player.STATE_ENDED
        val speed = if (loaded) transportState.speed else 1.0f
        val activeSleep = if (transportState.connected) sleepMinutes else null
        _state.value = OrationPlayerState.Ready(
            id = row.id,
            title = row.title,
            status = row.status,
            words = row.words,
            chunksDone = row.chunksDone,
            chunksTotal = row.chunksTotal,
            errorMessage = row.errorMessage,
            downloaded = row.downloaded,
            audioBytes = row.audioBytes,
            isLoaded = loaded,
            isPlaying = playing,
            catchingUp = ended && row.status == "NARRATING",
            speed = speed,
            sleepMinutes = activeSleep,
            sourceText = sourceText,
        )
        _timeline.value = computeTimeline(row, loaded)
        updateTicker(loaded && playing)
    }

    private fun updateTicker(active: Boolean) {
        if (!active || closed || currentRow == null) {
            tickerJob?.cancel()
            tickerJob = null
            return
        }
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive && !closed) {
                withContext(clockDispatcher) { delay(TICK_INTERVAL_MS) }
                val row = currentRow ?: break
                if (closed || !isCurrentLoaded() || !transportState.isPlaying) break
                transportState = transportState.copy(positionMs = transport.currentPositionMs())
                _timeline.value = computeTimeline(row, loaded = true)
            }
        }
    }

    private fun computeTimeline(row: OrationEntity, loaded: Boolean): PlaybackTimeline {
        val fallbackDurationMs = when {
            row.audioDurationMs > 0L -> row.audioDurationMs
            row.downloaded && row.audioBytes > 0L -> (row.audioBytes * 8L) / 64L
            else -> 0L
        }
        val liveDurationMs = transportState.durationMs
        val durationMs = if (loaded && liveDurationMs != C.TIME_UNSET && liveDurationMs > 0L) {
            liveDurationMs
        } else {
            fallbackDurationMs
        }
        val livePosMs = transportState.positionMs
        val rawPosMs = if (loaded && liveDurationMs != C.TIME_UNSET && livePosMs != C.TIME_UNSET) {
            livePosMs
        } else {
            persistence.resumePosition(id, row.playheadMs)
        }
        val positionMs = if (durationMs > 0L) rawPosMs.coerceIn(0L, durationMs) else 0L
        return PlaybackTimeline(positionMs = positionMs, durationMs = durationMs)
    }

    private fun isCurrentLoaded(): Boolean =
        !closed && currentRow != null && transportState.connected && transportState.mediaId == id

    override fun togglePlayPause() {
        if (closed) return
        val row = currentRow ?: return
        if (isCurrentLoaded()) {
            when {
                transportState.isPlaying -> transport.pause()
                transportState.playbackState == Player.STATE_ENDED && row.status != "NARRATING" -> {
                    transport.seekTo(0L)
                    transport.play()
                }
                else -> transport.play()
            }
        } else if (row.downloaded && row.audioBytes > 0L) {
            commands.play(id)
        }
    }

    override fun seekTo(positionMs: Long) {
        if (!isCurrentLoaded()) return
        val duration = _timeline.value.durationMs
        val target = if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        transport.seekTo(target)
        transportState = transportState.copy(positionMs = target)
        currentRow?.let { _timeline.value = computeTimeline(it, loaded = true) }
    }

    override fun skipBack() {
        if (!isCurrentLoaded()) return
        transport.seekBack()
    }

    override fun skipForward() {
        if (!isCurrentLoaded()) return
        transport.seekForward()
    }

    override fun setSpeed(speed: Float) {
        if (!isCurrentLoaded()) return
        transport.setPlaybackSpeed(speed)
    }

    override fun toggleSleepTimer(minutes: Int) {
        if (closed || currentRow == null) return
        val activeSleep = if (transportState.connected) sleepMinutes else null
        commands.sleepTimer(if (activeSleep == minutes) 0 else minutes)
    }

    override fun stopNarration() {
        if (closed || currentRow == null) return
        commands.stopNarration()
    }
    override fun close() {
        if (closed) return
        closed = true
        tickerJob?.cancel()
        tickerJob = null
        sourceLoadJob?.cancel()
        sourceLoadJob = null
        scope.cancel()
        transport.close()
    }

    private companion object {
        const val TICK_INTERVAL_MS = 250L
    }
}

@OptIn(UnstableApi::class)   // MediaController.sessionExtras is Media3 "unstable" API
internal class Media3PlayerTransport(context: Context) : PlayerTransport {
    private val app = context.applicationContext
    private var listener: PlayerTransport.Listener? = null
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var closed = false

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (closed || controller !== player) return
            listener?.onPlayerChanged(snapshotOf(player))
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            if (closed || this@Media3PlayerTransport.controller !== controller) return
            listener?.onExtrasChanged(sleepMinutesFromExtras(extras))
        }

        override fun onDisconnected(controller: MediaController) {
            if (closed) return
            controller.removeListener(playerListener)
            if (this@Media3PlayerTransport.controller === controller) {
                this@Media3PlayerTransport.controller = null
            }
            listener?.onDisconnected()
        }
    }

    override fun connect(listener: PlayerTransport.Listener) {
        if (closed) return
        this.listener = listener
        val token = SessionToken(app, ComponentName(app, NarrationService::class.java))
        val pending = MediaController.Builder(app, token)
            .setListener(controllerListener)
            .buildAsync()
        future = pending
        pending.addListener({
            if (closed) return@addListener
            val connected = try {
                pending.get()
            } catch (_: Exception) {
                if (!closed) listener.onDisconnected()
                return@addListener
            }
            if (closed) {
                connected.release()
                return@addListener
            }
            controller = connected
            connected.addListener(playerListener)
            listener.onConnected(
                snapshot = snapshotOf(connected),
                sleepMinutes = sleepMinutesFromExtras(connected.sessionExtras),
            )
        }, ContextCompat.getMainExecutor(app))
    }

    override fun currentPositionMs(): Long = controller?.currentPosition ?: 0L
    override fun play() { controller?.play() }
    override fun pause() { controller?.pause() }
    override fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }
    override fun seekBack() { controller?.seekBack() }
    override fun seekForward() { controller?.seekForward() }
    override fun setPlaybackSpeed(speed: Float) { controller?.setPlaybackSpeed(speed) }

    override fun close() {
        if (closed) return
        closed = true
        listener = null
        controller?.removeListener(playerListener)
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
    }

    private fun snapshotOf(player: Player) = TransportSnapshot(
        connected = true,
        mediaId = player.currentMediaItem?.mediaId,
        isPlaying = player.isPlaying,
        playbackState = player.playbackState,
        speed = player.playbackParameters.speed,
        positionMs = player.currentPosition,
        durationMs = player.duration,
    )
}

internal class InAppPlayerCommands(context: Context) : PlayerCommands {
    private val app = context.applicationContext

    override fun play(id: String) {
        InAppNarrationService.dispatch(app, InAppNarrationService.ACTION_PLAY, id = id)
    }

    override fun sleepTimer(minutes: Int) {
        InAppNarrationService.dispatch(app, InAppNarrationService.ACTION_SLEEP_TIMER, minutes = minutes)
    }

    override fun stopNarration() {
        InAppNarrationService.dispatch(app, InAppNarrationService.ACTION_STOP_NARRATION)
    }
}

internal fun sleepMinutesFromExtras(extras: Bundle?): Int? {
    if (extras == null || !extras.containsKey(EXTRA_SLEEP_MINUTES)) return null
    return extras.getInt(EXTRA_SLEEP_MINUTES, 0).takeIf { it > 0 }
}
