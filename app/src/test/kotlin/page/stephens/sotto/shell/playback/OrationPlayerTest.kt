package page.stephens.sotto.shell.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.SottoDatabase
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
class OrationPlayerTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        SottoDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val root = Files.createTempDirectory("oration-player").toFile()
    private val writes = QueuedDispatcher()
    private val storage = QueuedDispatcher()
    private val persistence = PlaybackPersistence(db, writes)
    private val transport = ControlledTransport()
    private val commands = RecordingCommands()
    private val players = mutableListOf<OrationPlayer>()

    private fun openPlayer(
        id: String,
        transport: ControlledTransport = this.transport,
        storageDispatcher: CoroutineDispatcher = storage,
    ): OrationPlayer = SavedOrationPlayer(
        id = id,
        db = db,
        root = root,
        persistence = persistence,
        transport = transport,
        commands = commands,
        storageDispatcher = storageDispatcher,
        playerDispatcher = Dispatchers.Main.immediate,
        clockDispatcher = Dispatchers.Main.immediate,
    ).also { players.add(it) }

    @After
    fun tearDown() {
        players.forEach { it.close() }
        flush()
        val shutdown = persistence.shutdownForTest()
        writes.runAll()
        assertTrue(shutdown.isCompleted)
        db.close()
        root.deleteRecursively()
    }

    @Test
    fun `pre-load timeline uses PlaybackPersistence resume position CBR fallback duration and reloads source on download`() {
        // 64_000 bytes at 64 kbps => (64_000 * 8) / 64 = 8_000 ms.
        seed(
            id = "a",
            status = "COMPLETE",
            downloaded = true,
            audioBytes = 64_000L,
            audioDurationMs = 0L,
            playheadMs = 2_000L,
            sourceText = "Initial manuscript.",
        )
        // Pending accepted position in PlaybackPersistence overrides older Room playheadMs.
        persistence.observe(PlaybackSnapshot("a", 5_500L, ended = false, playWhenReady = false, speed = 1f))

        val player = openPlayer("a")
        awaitState { (player.state.value as? OrationPlayerState.Ready)?.sourceText == "Initial manuscript." }

        val ready = player.state.value as OrationPlayerState.Ready
        assertFalse(ready.isLoaded)
        assertFalse(ready.isPlaying)
        assertEquals(1.0f, ready.speed)
        assertEquals("Initial manuscript.", ready.sourceText)
        assertEquals(PlaybackTimeline(positionMs = 5_500L, durationMs = 8_000L), player.timeline.value)

        // When loaded with TIME_UNSET duration, timeline still synthesizes saved position & positive row duration.
        db.orations().update(
            db.orations().byId("a")!!.copy(audioDurationMs = 15_000L, playheadMs = 6_000L),
        )
        transport.connectWith(
            TransportSnapshot(
                connected = true,
                mediaId = "a",
                isPlaying = false,
                playbackState = Player.STATE_BUFFERING,
                speed = 1.25f,
                positionMs = 0L,
                durationMs = C.TIME_UNSET,
            ),
            sleepMinutes = null,
        )
        awaitState { player.timeline.value.durationMs == 15_000L }
        assertTrue((player.state.value as OrationPlayerState.Ready).isLoaded)
        assertEquals(1.25f, (player.state.value as OrationPlayerState.Ready).speed)
        assertEquals(PlaybackTimeline(positionMs = 5_500L, durationMs = 15_000L), player.timeline.value)

        // Unclamped resume position above duration is clamped cleanly.
        persistence.observe(PlaybackSnapshot("a", 25_000L, ended = false, playWhenReady = false, speed = 1f))
        transport.emitPlayer(
            TransportSnapshot(
                connected = true,
                mediaId = "other",
                isPlaying = true,
                playbackState = Player.STATE_READY,
                speed = 1.5f,
                positionMs = 1_000L,
                durationMs = 20_000L,
            ),
        )
        flush()
        assertFalse((player.state.value as OrationPlayerState.Ready).isLoaded)
        assertEquals(PlaybackTimeline(positionMs = 15_000L, durationMs = 15_000L), player.timeline.value)

        // Source text reloads when downloaded transitions from false to true.
        seed(
            id = "srv",
            status = "COMPLETE",
            downloaded = false,
            audioBytes = 64_000L,
            audioDurationMs = 10_000L,
            playheadMs = 1_500L,
            sourceText = null,
        )
        val serverPlayer = openPlayer("srv", transport = ControlledTransport())
        awaitState { serverPlayer.state.value is OrationPlayerState.Ready }
        val pendingReady = serverPlayer.state.value as OrationPlayerState.Ready
        assertEquals("", pendingReady.sourceText)
        assertEquals(PlaybackTimeline(positionMs = 1_500L, durationMs = 10_000L), serverPlayer.timeline.value)

        File(root, "srv").mkdirs()
        File(root, "srv/source.md").writeText("Downloaded manuscript.")
        db.orations().setDownloaded("srv", 64_000L)
        awaitState { (serverPlayer.state.value as? OrationPlayerState.Ready)?.sourceText == "Downloaded manuscript." }
        assertEquals("Downloaded manuscript.", (serverPlayer.state.value as OrationPlayerState.Ready).sourceText)
    }

    @Test
    fun `cross-Oration isolation prevents mutating another playing Oration and gates ActionPlay on local audio`() {
        seed(id = "unready", status = "QUEUED", downloaded = true, audioBytes = 0L)
        seed(id = "a", status = "COMPLETE", downloaded = true, audioBytes = 64_000L, audioDurationMs = 20_000L)

        // Another Oration ("other") is actively playing at 1.5x.
        transport.connectWith(
            TransportSnapshot(
                connected = true,
                mediaId = "other",
                isPlaying = true,
                playbackState = Player.STATE_READY,
                speed = 1.5f,
                positionMs = 4_000L,
                durationMs = 30_000L,
            ),
        )

        val unreadyTransport = ControlledTransport()
        val unreadyPlayer = openPlayer("unready", transport = unreadyTransport)
        awaitState { unreadyPlayer.state.value is OrationPlayerState.Ready }
        unreadyTransport.connectWith(transport.snapshot)
        unreadyPlayer.togglePlayPause()
        assertTrue(commands.plays.isEmpty())
        assertTrue(unreadyTransport.calls.isEmpty())

        val player = openPlayer("a")
        awaitState { player.state.value is OrationPlayerState.Ready }
        transport.connectWith(transport.snapshot)

        val ready = player.state.value as OrationPlayerState.Ready
        assertFalse(ready.isLoaded)
        assertFalse(ready.isPlaying)
        assertEquals(1.0f, ready.speed)

        // All transport-mutating intents are strict no-ops when another Oration is active.
        player.seekTo(9_000L)
        player.skipBack()
        player.skipForward()
        player.setSpeed(2.0f)
        assertTrue(transport.calls.isEmpty())

        // togglePlayPause dispatches ACTION_PLAY for "a" instead of pausing "other".
        player.togglePlayPause()
        assertEquals(listOf("a"), commands.plays)
        assertTrue(transport.calls.isEmpty())

        // Once "a" loads in the session, transport actions target "a".
        transport.emitPlayer(
            TransportSnapshot(
                connected = true,
                mediaId = "a",
                isPlaying = true,
                playbackState = Player.STATE_READY,
                speed = 1.25f,
                positionMs = 2_000L,
                durationMs = 20_000L,
            ),
        )
        assertTrue((player.state.value as OrationPlayerState.Ready).isLoaded)
        assertTrue((player.state.value as OrationPlayerState.Ready).isPlaying)
        assertEquals(1.25f, (player.state.value as OrationPlayerState.Ready).speed)

        player.seekTo(9_000L)
        player.skipBack()
        player.skipForward()
        player.setSpeed(1.5f)
        player.togglePlayPause()
        assertEquals(
            listOf("seekTo:9000", "seekBack", "seekForward", "speed:1.5", "pause"),
            transport.calls,
        )

        transport.emitPlayer(transport.snapshot.copy(isPlaying = false))
        player.togglePlayPause()
        assertEquals("play", transport.calls.last())
    }

    @Test
    fun `STATE_ENDED enters catchingUp while NARRATING and replays from zero when COMPLETE`() {
        seed(id = "narr", status = "NARRATING", downloaded = true, audioBytes = 32_000L, audioDurationMs = 4_000L)
        val player = openPlayer("narr")
        awaitState { player.state.value is OrationPlayerState.Ready }
        transport.connectWith(
            TransportSnapshot(
                connected = true,
                mediaId = "narr",
                isPlaying = false,
                playbackState = Player.STATE_ENDED,
                speed = 1.0f,
                positionMs = 4_000L,
                durationMs = 4_000L,
            ),
        )
        val catchingUpState = player.state.value as OrationPlayerState.Ready
        assertTrue(catchingUpState.catchingUp)

        player.togglePlayPause()
        assertFalse(transport.calls.contains("seekTo:0"))
        assertTrue((player.state.value as OrationPlayerState.Ready).catchingUp)

        db.orations().setStatus("narr", "COMPLETE", 30L)
        awaitState { (player.state.value as OrationPlayerState.Ready).status == "COMPLETE" }
        assertFalse((player.state.value as OrationPlayerState.Ready).catchingUp)

        transport.calls.clear()
        player.togglePlayPause()
        assertEquals(listOf("seekTo:0", "play"), transport.calls)
    }

    @Test
    fun `active-only playhead ticker runs only while loaded and playing and stops on every stop condition`() {
        seed(id = "a", status = "COMPLETE", downloaded = true, audioBytes = 64_000L, audioDurationMs = 20_000L)
        val player = openPlayer("a")
        awaitState { player.state.value is OrationPlayerState.Ready }

        // Not connected yet -> no polling.
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(0, transport.positionReadCount)

        // Connected & loaded but paused -> still no polling.
        transport.connectWith(
            TransportSnapshot(
                connected = true,
                mediaId = "a",
                isPlaying = false,
                playbackState = Player.STATE_READY,
                speed = 1.0f,
                positionMs = 1_000L,
                durationMs = 20_000L,
            ),
        )
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(0, transport.positionReadCount)

        fun playWithPosition(posMs: Long) {
            transport.emitPlayer(
                TransportSnapshot(
                    connected = true,
                    mediaId = "a",
                    isPlaying = true,
                    playbackState = Player.STATE_READY,
                    speed = 1.0f,
                    positionMs = posMs,
                    durationMs = 20_000L,
                ),
            )
        }

        // Playing -> ticker advances timeline from currentPositionMs().
        playWithPosition(2_000L)
        transport.snapshot = transport.snapshot.copy(positionMs = 2_500L)
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        assertTrue(transport.positionReadCount > 0)
        assertEquals(2_500L, player.timeline.value.positionMs)

        // 1. Pause stops ticker.
        transport.emitPlayer(transport.snapshot.copy(isPlaying = false))
        val readsAfterPause = transport.positionReadCount
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(readsAfterPause, transport.positionReadCount)

        // 2. STATE_ENDED stops ticker.
        playWithPosition(3_000L)
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        assertTrue(transport.positionReadCount > readsAfterPause)
        transport.emitPlayer(transport.snapshot.copy(isPlaying = false, playbackState = Player.STATE_ENDED))
        val readsAfterEnded = transport.positionReadCount
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(readsAfterEnded, transport.positionReadCount)

        // 3. Item switch stops ticker.
        playWithPosition(4_000L)
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        assertTrue(transport.positionReadCount > readsAfterEnded)
        transport.emitPlayer(transport.snapshot.copy(mediaId = "other", isPlaying = true))
        val readsAfterSwitch = transport.positionReadCount
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(readsAfterSwitch, transport.positionReadCount)

        // 4. Disconnect stops ticker.
        playWithPosition(5_000L)
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        assertTrue(transport.positionReadCount > readsAfterSwitch)
        transport.emitDisconnected()
        val readsAfterDisconnect = transport.positionReadCount
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(readsAfterDisconnect, transport.positionReadCount)

        // 5. Missing (deleted row) stops ticker.
        transport.connectWith(
            TransportSnapshot(
                connected = true,
                mediaId = "a",
                isPlaying = true,
                playbackState = Player.STATE_READY,
                speed = 1.0f,
                positionMs = 6_000L,
                durationMs = 20_000L,
            ),
        )
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        assertTrue(transport.positionReadCount > readsAfterDisconnect)
        db.orations().delete("a")
        awaitState { player.state.value is OrationPlayerState.Missing }
        val readsAfterMissing = transport.positionReadCount
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(readsAfterMissing, transport.positionReadCount)

        // 6. close() stops ticker.
        seed(id = "b", status = "COMPLETE", downloaded = true, audioBytes = 64_000L, audioDurationMs = 20_000L)
        val transportB = ControlledTransport()
        val playerB = openPlayer("b", transport = transportB)
        awaitState { playerB.state.value is OrationPlayerState.Ready }
        transportB.connectWith(
            TransportSnapshot(
                connected = true,
                mediaId = "b",
                isPlaying = true,
                playbackState = Player.STATE_READY,
                speed = 1.0f,
                positionMs = 1_000L,
                durationMs = 20_000L,
            ),
        )
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        assertTrue(transportB.positionReadCount > 0)
        playerB.close()
        val readsAfterClose = transportB.positionReadCount
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(readsAfterClose, transportB.positionReadCount)
    }

    @Test
    fun `sleep timer extras synchronize across connect extras changes and disconnect and stopNarration dispatches`() {
        seed(id = "a", status = "NARRATING", downloaded = true, audioBytes = 32_000L, audioDurationMs = 4_000L)
        val player = openPlayer("a")
        awaitState { player.state.value is OrationPlayerState.Ready }

        transport.connectWith(
            TransportSnapshot(
                connected = true,
                mediaId = "a",
                isPlaying = true,
                playbackState = Player.STATE_READY,
                speed = 1.5f,
                positionMs = 1_000L,
                durationMs = 4_000L,
            ),
            sleepMinutes = 15,
        )
        assertEquals(15, (player.state.value as OrationPlayerState.Ready).sleepMinutes)

        // Tapping active sleep chip cancels (0); tapping another arms it.
        player.toggleSleepTimer(15)
        player.toggleSleepTimer(30)
        assertEquals(listOf(0, 30), commands.sleeps)

        transport.emitExtras(30)
        assertEquals(30, (player.state.value as OrationPlayerState.Ready).sleepMinutes)

        transport.emitExtras(null)
        assertNull((player.state.value as OrationPlayerState.Ready).sleepMinutes)

        transport.emitExtras(60)
        transport.emitDisconnected()
        val disconnected = player.state.value as OrationPlayerState.Ready
        assertFalse(disconnected.isLoaded)
        assertFalse(disconnected.isPlaying)
        assertEquals(1.0f, disconnected.speed)
        assertNull(disconnected.sleepMinutes)

        player.stopNarration()
        assertEquals(1, commands.stopCount)
    }

    @Test
    fun `Missing cleanup and early close cancel pending source loads and ignore late callbacks`() {
        seed(id = "gone", status = "COMPLETE", downloaded = true, audioBytes = 64_000L, audioDurationMs = 8_000L)
        val player = openPlayer("gone")
        awaitState { player.state.value is OrationPlayerState.Ready }
        transport.connectWith(
            TransportSnapshot(
                connected = true,
                mediaId = "gone",
                isPlaying = true,
                playbackState = Player.STATE_READY,
                speed = 1.25f,
                positionMs = 3_000L,
                durationMs = 8_000L,
            ),
            sleepMinutes = 15,
        )
        assertEquals(PlaybackTimeline(3_000L, 8_000L), player.timeline.value)

        db.orations().delete("gone")
        awaitState { player.state.value is OrationPlayerState.Missing }
        assertEquals(PlaybackTimeline(0L, 0L), player.timeline.value)

        // Actions on Missing are no-ops.
        transport.calls.clear()
        commands.plays.clear()
        commands.sleeps.clear()
        commands.stopCount = 0
        player.togglePlayPause()
        player.seekTo(1_000L)
        player.skipBack()
        player.skipForward()
        player.setSpeed(2.0f)
        player.toggleSleepTimer(15)
        player.stopNarration()
        assertTrue(transport.calls.isEmpty())
        assertTrue(commands.plays.isEmpty())
        assertTrue(commands.sleeps.isEmpty())
        assertEquals(0, commands.stopCount)

        // Early close before transport connects or source loads completes cleanly and ignores late events.
        seed(id = "early", status = "COMPLETE", downloaded = true, audioBytes = 64_000L, audioDurationMs = 8_000L)
        val earlyTransport = ControlledTransport()
        val heldStorage = QueuedDispatcher()
        val earlyPlayer = openPlayer("early", transport = earlyTransport, storageDispatcher = heldStorage)
        awaitState { earlyPlayer.state.value is OrationPlayerState.Ready }
        val listenerBeforeClose = earlyTransport.listener!!
        earlyPlayer.close()
        earlyPlayer.close() // Idempotent.
        assertEquals(1, earlyTransport.closeCount)

        heldStorage.runAll()
        listenerBeforeClose.onConnected(
            TransportSnapshot(
                connected = true,
                mediaId = "early",
                isPlaying = true,
                playbackState = Player.STATE_READY,
                speed = 1.5f,
                positionMs = 2_000L,
                durationMs = 8_000L,
            ),
            sleepMinutes = 30,
        )
        listenerBeforeClose.onExtrasChanged(60)
        shadowOf(Looper.getMainLooper()).idle()
        val afterEarlyClose = earlyPlayer.state.value as OrationPlayerState.Ready
        assertFalse(afterEarlyClose.isLoaded)
        assertEquals("", afterEarlyClose.sourceText)
        assertNull(afterEarlyClose.sleepMinutes)
        earlyPlayer.toggleSleepTimer(15)
        earlyPlayer.stopNarration()
        assertTrue(commands.sleeps.isEmpty())
        assertEquals(0, commands.stopCount)
    }

    private fun seed(
        id: String,
        status: String = "COMPLETE",
        downloaded: Boolean = true,
        audioBytes: Long = 64_000L,
        audioDurationMs: Long = 0L,
        playheadMs: Long = 0L,
        sourceText: String? = "Source text for $id.",
    ): OrationEntity {
        val row = OrationEntity(
            id = id,
            title = "Title $id",
            status = status,
            voiceId = "af_heart",
            words = 42,
            chunksTotal = 1,
            chunksDone = if (status == "COMPLETE") 1 else 0,
            audioBytes = audioBytes,
            audioDurationMs = audioDurationMs,
            createdAt = 10L,
            updatedAt = 20L,
            playheadMs = playheadMs,
            downloaded = downloaded,
        )
        db.orations().insert(row)
        if (sourceText != null) {
            File(root, id).mkdirs()
            File(root, "$id/source.md").writeText(sourceText)
        }
        return row
    }

    private fun flush() {
        repeat(10) {
            storage.runAll()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun awaitState(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!cond()) {
            flush()
            if (System.nanoTime() > deadline) break
            Thread.sleep(10)
        }
        flush()
        assertTrue("Timed out waiting for player state", cond())
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = mutableListOf<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeAt(0).run() }
    }

    private class ControlledTransport : PlayerTransport {
        var listener: PlayerTransport.Listener? = null
        var snapshot = TransportSnapshot()
        var sleepMinutes: Int? = null
        var connectCount = 0
        var closeCount = 0
        var positionReadCount = 0
        val calls = mutableListOf<String>()

        override fun connect(listener: PlayerTransport.Listener) {
            connectCount++
            this.listener = listener
        }

        fun connectWith(snapshot: TransportSnapshot, sleepMinutes: Int? = null) {
            this.snapshot = snapshot
            this.sleepMinutes = sleepMinutes
            listener?.onConnected(snapshot, sleepMinutes)
        }

        fun emitPlayer(snapshot: TransportSnapshot) {
            this.snapshot = snapshot
            listener?.onPlayerChanged(snapshot)
        }

        fun emitExtras(minutes: Int?) {
            this.sleepMinutes = minutes
            listener?.onExtrasChanged(minutes)
        }

        fun emitDisconnected() {
            this.snapshot = TransportSnapshot()
            this.sleepMinutes = null
            listener?.onDisconnected()
        }

        override fun currentPositionMs(): Long {
            positionReadCount++
            return snapshot.positionMs
        }

        override fun play() { calls.add("play") }
        override fun pause() { calls.add("pause") }
        override fun seekTo(positionMs: Long) {
            calls.add("seekTo:$positionMs")
            snapshot = snapshot.copy(positionMs = positionMs)
        }
        override fun seekBack() { calls.add("seekBack") }
        override fun seekForward() { calls.add("seekForward") }
        override fun setPlaybackSpeed(speed: Float) { calls.add("speed:$speed") }
        override fun close() { closeCount++; listener = null }
    }

    private class RecordingCommands : PlayerCommands {
        val plays = mutableListOf<String>()
        val sleeps = mutableListOf<Int>()
        var stopCount = 0
        override fun play(id: String) { plays.add(id) }
        override fun sleepTimer(minutes: Int) { sleeps.add(minutes) }
        override fun stopNarration() { stopCount++ }
    }
}
