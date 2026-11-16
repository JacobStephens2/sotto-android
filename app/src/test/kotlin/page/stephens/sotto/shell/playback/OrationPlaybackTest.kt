package page.stephens.sotto.shell.playback

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import page.stephens.sotto.engine.narrate.NarrationFacts
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.SottoDatabase
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
class OrationPlaybackTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), SottoDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val root = Files.createTempDirectory("oration-playback").toFile()
    private val storage = QueuedDispatcher()
    private val writes = QueuedDispatcher()
    private val facts = MutableStateFlow(NarrationFacts())
    private val persistence = PlaybackPersistence(db, writes)
    private val output = ControlledOutput()
    private val owners = mutableListOf<OrationPlayback>()
    private val playerQueues = mutableListOf<QueuedDispatcher>()

    private fun owner(
        adapter: ControlledOutput = output,
        playerDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    ): OrationPlayback = SavedOrationPlayback(
        db, root, adapter, facts, persistence, storageDispatcher = storage,
        playerDispatcher = playerDispatcher,
    ).also {
        owners.add(it)
        if (playerDispatcher is QueuedDispatcher) playerQueues.add(playerDispatcher)
    }

    @After
    fun tearDown() {
        owners.forEach { it.close() }
        flush()
        val shutdown = persistence.shutdownForTest()
        writes.runAll()
        assertTrue(shutdown.isCompleted)
        db.close()
        root.deleteRecursively()
    }

    @Test
    fun `explicit play loads saved audio at the retained position`() {
        seed("a", position = 1200)
        val playback = owner()
        playback.play("a")
        assertTrue(output.loads.isEmpty())
        flush()
        assertEquals(listOf("a"), output.loads.map { it.id })
        assertEquals(1200L, output.state.positionMs)
        assertTrue(output.state.playWhenReady)
    }

    @Test
    fun `an unavailable newest explicit request never revives an older lookup`() {
        seed("current")
        seed("older")
        val playback = owner()
        playback.play("current")
        flush()
        playback.play("older")
        playback.play("missing")
        storage.run(1)
        shadowOf(Looper.getMainLooper()).idle()
        storage.run()
        flush()
        assertEquals("current", output.state.id)
        assertEquals(listOf("current"), output.loads.map { it.id })
    }

    @Test
    fun `ended audio reloads only durable growth including an idle workers final tail`() {
        seed("a")
        val playback = owner()
        playback.play("a")
        flush()
        output.state = output.state.copy(positionMs = 9000, ended = true, speed = 1.5f)
        tick()
        flush()
        assertEquals(1, output.loads.size)
        grow("a", 16)
        tick()
        // A pause while the real storage lookup waits must win.
        output.state = output.state.copy(playWhenReady = false, speed = 1.8f)
        flush()
        assertEquals(2, output.loads.size)
        assertEquals(16L, output.loads.last().durableBytes)
        assertEquals(9000L, output.state.positionMs)
        assertEquals(1.8f, output.state.speed)
        assertFalse(output.state.playWhenReady)
        assertEquals(9000L, db.orations().byId("a")!!.playheadMs)
        assertEquals(20L, db.orations().byId("a")!!.updatedAt)
    }

    @Test
    fun `deletion immediately clears playback and fences reads and writes across gate recreation`() {
        seed("gate")
        val playerQueue = QueuedDispatcher()
        val playback = owner(playerDispatcher = playerQueue)
        playback.play("gate")
        flush()
        output.state = output.state.copy(positionMs = 4300)
        tick() // Accept a position, but hold its real Room transaction.
        playerQueue.runAll()
        playback.play("gate")
        playerQueue.runAll()
        storage.run() // The old row is read; player continuation stays queued.
        assertEquals(1, output.loads.size)
        facts.value = NarrationFacts(deletingIds = setOf("gate"))
        playback.stopForDeletion("gate")
        assertNull(output.state.id)
        assertEquals(1, output.clears)
        playback.play("gate") // Admission during deletion stays rejected.
        db.orations().delete("gate")
        File(root, "gate").deleteRecursively()
        seed("gate")
        facts.value = NarrationFacts()
        flush()
        assertNull(output.state.id)
        assertEquals(0L, db.orations().byId("gate")!!.playheadMs)
        playback.play("gate")
        flush()
        assertEquals("gate", output.state.id)
        assertTrue(output.state.playWhenReady)
    }

    @Test
    fun `close captures the final position and replacement owners persist in acceptance order`() {
        seed("a")
        val first = owner()
        first.play("a")
        flush()
        output.state = output.state.copy(positionMs = 1000)
        val oldDrain = first.close()
        assertFalse(oldDrain.isCompleted)
        val replacementOutput = ControlledOutput()
        val replacement = owner(replacementOutput)
        replacement.play("a")
        storage.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        replacementOutput.state = replacementOutput.state.copy(positionMs = 2400)
        val newDrain = replacement.close()
        assertFalse(newDrain.isCompleted)
        writes.runAll()
        assertTrue(oldDrain.isCompleted)
        assertTrue(newDrain.isCompleted)
        assertEquals(2400L, db.orations().byId("a")!!.playheadMs)
        assertEquals(20L, db.orations().byId("a")!!.updatedAt)
    }

    @Test
    fun `replacing and cancelling queued sleep deadlines fences them and accepted pauses stay logged after close`() {
        seed("a")
        val playerQueue = QueuedDispatcher()
        val playback = owner(playerDispatcher = playerQueue)
        playback.play("a")
        flush()
        playback.sleep(15)
        flush()
        shadowOf(Looper.getMainLooper()).idleFor(15, TimeUnit.MINUTES)
        // Deadline reached the player dispatcher, but has not been accepted.
        playback.sleep(30)
        flush()
        assertEquals(0, output.pauses)
        assertTrue(db.joblog().recent().isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.MINUTES)
        playback.sleep(0)
        flush()
        assertEquals(0, output.pauses)
        assertTrue(db.joblog().recent().isEmpty())
        playback.sleep(15)
        flush()
        shadowOf(Looper.getMainLooper()).idleFor(15, TimeUnit.MINUTES)
        playerQueue.runAll()
        assertEquals(1, output.pauses)
        assertFalse(output.state.playWhenReady)
        assertTrue(db.joblog().recent().isEmpty()) // Log is still off-thread work.
        val drained = playback.close()
        writes.runAll()
        assertTrue(drained.isCompleted)
        assertEquals(listOf("sleep-timer"), db.joblog().recent().map { it.event })
        assertEquals("paused after 15 min", db.joblog().recent().single().title)
    }

    @Test
    fun `newest explicit selection wins in either lookup completion order`() {
        for (newerFirst in listOf(false, true)) {
            seed("old-$newerFirst")
            seed("new-$newerFirst")
            val adapter = ControlledOutput()
            val playback = owner(adapter)
            playback.play("old-$newerFirst")
            playback.play("new-$newerFirst")
            storage.run(if (newerFirst) 1 else 0)
            shadowOf(Looper.getMainLooper()).idle()
            flush()
            assertEquals(listOf("new-$newerFirst"), adapter.loads.map { it.id })
            playback.close()
            flush()
        }
    }

    @Test
    fun `listen now yields to pending and selected explicit choices`() {
        val automatic = seed("automatic")
        seed("explicit")
        val playback = owner()
        playback.play("explicit")
        playback.listenNow(automatic)
        flush()
        playback.listenNow(automatic)
        flush()
        assertEquals(listOf("explicit"), output.loads.map { it.id })
    }

    @Test
    fun `explicit choice supersedes a pending automatic selection`() {
        val automatic = seed("automatic")
        seed("explicit")
        val playback = owner()
        playback.listenNow(automatic)
        playback.play("explicit")
        storage.run(1)
        shadowOf(Looper.getMainLooper()).idle()
        flush()
        assertEquals(listOf("explicit"), output.loads.map { it.id })
    }

    @Test
    fun `listen now can start saved audio after an unavailable explicit request finishes`() {
        val automatic = seed("automatic")
        val playback = owner()
        playback.play("missing")
        playback.listenNow(automatic)
        flush()
        assertTrue(output.loads.isEmpty())
        playback.listenNow(automatic)
        flush()
        assertEquals("automatic", output.state.id)
        playback.listenNow(seed("another"))
        flush()
        assertEquals(listOf("automatic"), output.loads.map { it.id })
    }

    @Test
    fun `missing server only empty and unavailable audio retain current media`() {
        seed("current")
        seed("remote", downloaded = false)
        seed("empty", bytes = 0)
        seed("missing-file")
        File(root, "missing-file/audio.aac").delete()
        seed("short-file")
        File(root, "short-file/audio.aac").writeBytes(byteArrayOf(1))
        val playback = owner()
        playback.play("current")
        flush()
        for (id in listOf("missing", "remote", "empty", "missing-file", "short-file")) {
            playback.play(id)
            flush()
            assertEquals("current", output.state.id)
        }
        assertEquals(1, output.loads.size)
    }

    @Test
    fun `positions retain their Oration identity while writes wait across a switch`() {
        seed("a")
        seed("b", position = 2000)
        val playback = owner()
        playback.play("a")
        flush()
        output.state = output.state.copy(positionMs = 1100, speed = 1.6f)
        tick()
        playback.play("b")
        storage.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2000L, output.state.positionMs)
        assertEquals(1.6f, output.state.speed)
        output.state = output.state.copy(positionMs = 7700)
        tick()
        writes.runAll()
        assertEquals(1100L, db.orations().byId("a")!!.playheadMs)
        assertEquals(7700L, db.orations().byId("b")!!.playheadMs)
        assertEquals(20L, db.orations().byId("a")!!.updatedAt)
        assertEquals(20L, db.orations().byId("b")!!.updatedAt)
    }

    @Test
    fun `a seek away from the frontier wins over a pending refresh`() {
        seed("a")
        val playback = owner()
        playback.play("a")
        flush()
        output.state = output.state.copy(positionMs = 9000, ended = true)
        grow("a", 16)
        tick()
        output.state = output.state.copy(positionMs = 3000, ended = false)
        flush()
        assertEquals(1, output.loads.size)
        assertEquals(3000L, output.state.positionMs)
        // Some transports retain ended while processing a seek. Position also fences it.
        output.state = output.state.copy(positionMs = 9000, ended = true)
        tick()
        output.state = output.state.copy(positionMs = 2000)
        flush()
        assertEquals(1, output.loads.size)
        output.state = output.state.copy(positionMs = 9000, ended = true, speed = 1.4f)
        tick()
        flush()
        assertEquals(2, output.loads.size)
        assertTrue(output.state.playWhenReady)
        assertEquals(1.4f, output.state.speed)
    }

    @Test
    fun `switching selection wins over a pending growing audio refresh`() {
        seed("a")
        seed("b")
        val playback = owner()
        playback.play("a")
        flush()
        output.state = output.state.copy(positionMs = 9000, ended = true)
        grow("a", 16)
        tick()
        playback.play("b")
        storage.run(1)
        shadowOf(Looper.getMainLooper()).idle()
        flush()
        assertEquals(listOf("a", "b"), output.loads.map { it.id })
    }

    @Test
    fun `deleting pending listen now fences its old row and allows a fresh gate`() {
        val row = seed("gate")
        val playback = owner()
        playback.listenNow(row)
        playback.stopForDeletion("gate")
        db.orations().delete("gate")
        File(root, "gate").deleteRecursively()
        seed("gate")
        flush()
        assertTrue(output.loads.isEmpty())
        playback.play("gate")
        flush()
        assertEquals("gate", output.state.id)
    }

    @Test
    fun `deleting current audio preserves a newer pending explicit choice`() {
        seed("a")
        seed("b")
        val playback = owner()
        playback.play("a")
        flush()
        playback.play("b")
        playback.stopForDeletion("a")
        assertNull(output.state.id)
        flush()
        assertEquals("b", output.state.id)
    }

    @Test
    fun `close fences queued deadlines refreshes selections and all later commands`() {
        seed("a")
        val b = seed("b")
        val playerQueue = QueuedDispatcher()
        val playback = owner(playerDispatcher = playerQueue)
        playback.play("a")
        flush()
        playback.sleep(15)
        flush()
        output.state = output.state.copy(positionMs = 9000, ended = true)
        grow("a", 16)
        shadowOf(Looper.getMainLooper()).idleFor(15, TimeUnit.MINUTES)
        val drained = playback.close()
        output.released = true
        playback.play("b")
        playback.listenNow(b)
        playback.stopForDeletion("a")
        playback.sleep(30)
        assertSame(drained, playback.close())
        flush()
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.MINUTES)
        flush()
        assertTrue(drained.isCompleted)
        assertEquals(1, output.loads.size)
        assertEquals(0, output.pauses)
        assertTrue(db.joblog().recent().isEmpty())
        assertEquals(9000L, db.orations().byId("a")!!.playheadMs)
    }

    @Test
    fun `close fences a completed lookup whose player continuation is queued`() {
        seed("a")
        val playerQueue = QueuedDispatcher()
        val playback = owner(playerDispatcher = playerQueue)
        playback.play("a")
        playerQueue.runAll()
        storage.run()
        assertTrue(output.loads.isEmpty())
        val drained = playback.close()
        output.released = true
        flush()
        assertTrue(drained.isCompleted)
        assertTrue(output.loads.isEmpty())
    }

    @Test
    fun `close fences growing audio work already waiting on storage`() {
        seed("a")
        val playback = owner()
        playback.play("a")
        flush()
        output.state = output.state.copy(positionMs = 9000, ended = true)
        grow("a", 16)
        tick()
        val drained = playback.close()
        output.released = true
        flush()
        assertTrue(drained.isCompleted)
        assertEquals(1, output.loads.size)
        assertEquals(9000L, db.orations().byId("a")!!.playheadMs)
    }

    @Test
    fun `replacement deletion fences an old owners queued final position`() {
        seed("gate")
        val first = owner()
        first.play("gate")
        flush()
        output.state = output.state.copy(positionMs = 1000)
        tick()
        writes.runAll()
        assertEquals(1000L, db.orations().byId("gate")!!.playheadMs)
        output.state = output.state.copy(positionMs = 1500)
        val oldDrain = first.close()
        val replacementOutput = ControlledOutput()
        val replacement = owner(replacementOutput)
        replacement.stopForDeletion("gate")
        db.orations().delete("gate")
        File(root, "gate").deleteRecursively()
        seed("gate")
        replacement.play("gate")
        flush()
        assertTrue(oldDrain.isCompleted)
        assertEquals(0L, db.orations().byId("gate")!!.playheadMs)
        assertEquals("gate", replacementOutput.state.id)
        assertEquals(0L, replacementOutput.state.positionMs)
    }

    @Test
    fun `storage failures remain local and persistence still drains`() {
        seed("a")
        val playback = owner()
        playback.play("a")
        flush()
        db.openHelper.writableDatabase.execSQL("DROP TABLE orations")
        playback.play("missing")
        flush()
        assertEquals("a", output.state.id)
        playback.sleep(15)
        shadowOf(Looper.getMainLooper()).idleFor(15, TimeUnit.MINUTES)
        output.state = output.state.copy(positionMs = 1000)
        val drained = playback.close()
        flush()
        assertEquals(1, output.pauses)
        assertTrue(drained.isCompleted)
        assertTrue(ShadowLog.getLogsForTag("OrationPlayback").any { it.msg == "Saved audio lookup failed" })
        assertTrue(ShadowLog.getLogsForTag("OrationPlayback").any { it.msg == "Playback persistence failed" })
    }

    @Test
    fun `returning to an Oration restores its last accepted position before queued writes drain`() {
        seed("a", position = 100)
        seed("b", position = 2000)
        val playback = owner()
        playback.play("a")
        flush()
        output.state = output.state.copy(positionMs = 1100)
        playback.play("b")
        storage.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("b", output.state.id)
        // A's 1100 ms observation is accepted, but its FIFO write is held.
        playback.play("a")
        storage.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("a", output.state.id)
        assertEquals(1100L, output.state.positionMs)
    }

    @Test
    fun `switching back restores the accepted position before writes drain`() {
        seed("a")
        seed("b")
        val playback = owner()
        playback.play("a")
        flush()
        output.state = output.state.copy(positionMs = 4200)

        playback.play("b")
        storage.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("b", output.state.id)
        // A's switch observation is accepted, but its FIFO write is held.
        assertEquals(0L, db.orations().byId("a")!!.playheadMs)

        playback.play("a")
        storage.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("a", output.state.id)
        assertEquals(4200L, output.state.positionMs)
    }

    @Test
    fun `a replacement restores the final accepted position before writes drain`() {
        seed("a")
        val first = owner()
        first.play("a")
        flush()
        output.state = output.state.copy(positionMs = 5000)
        val firstDrained = first.close()
        assertFalse(firstDrained.isCompleted)

        val replacementOutput = ControlledOutput()
        val replacement = owner(replacementOutput)
        replacement.play("a")
        storage.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("a", replacementOutput.state.id)
        assertEquals(5000L, replacementOutput.state.positionMs)
    }

    private fun grow(id: String, bytes: Long) {
        File(root, "$id/audio.aac").writeBytes(ByteArray(bytes.toInt()))
        db.orations().setDownloaded(id, bytes)
    }

    private fun tick() { shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS) }

    private fun seed(id: String, bytes: Long = 8, position: Long = 0, downloaded: Boolean = true): OrationEntity {
        val row = OrationEntity(id, id, "COMPLETE", "af_heart", 3, 1,
            audioBytes = bytes, createdAt = 10, updatedAt = 20, playheadMs = position, downloaded = downloaded)
        db.orations().insert(row)
        File(root, "$id/audio.aac").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(bytes.toInt())) }
        return row
    }

    private fun flush() {
        repeat(10) {
            playerQueues.forEach { it.runAll() }
            storage.runAll()
            shadowOf(Looper.getMainLooper()).idle()
            writes.runAll()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = mutableListOf<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun run(index: Int = 0) { tasks.removeAt(index).run() }
        fun runAll() { while (tasks.isNotEmpty()) run() }
    }

    private class ControlledOutput : PlaybackOutput {
        var released = false
        var state = PlaybackSnapshot(null, 0, false, false, 1f)
        val loads = mutableListOf<SavedAudio>()
        var pauses = 0
        var clears = 0
        override fun snapshot(): PlaybackSnapshot { checkAvailable(); return state }
        override fun replace(audio: SavedAudio, positionMs: Long, speed: Float, playWhenReady: Boolean) {
            checkAvailable()
            loads.add(audio)
            state = PlaybackSnapshot(audio.id, positionMs, false, playWhenReady, speed)
        }
        override fun pause() { checkAvailable(); pauses++; state = state.copy(playWhenReady = false) }
        override fun stopAndClear() {
            checkAvailable(); clears++; state = state.copy(id = null, positionMs = 0, playWhenReady = false)
        }
        private fun checkAvailable() {
            check(!released) { "Output was released" }
            check(Looper.myLooper() == Looper.getMainLooper()) { "Output requires the player thread" }
        }
    }
}
