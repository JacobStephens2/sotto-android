package page.stephens.sotto.shell.playback

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import page.stephens.sotto.engine.narrate.NarrationFacts
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.SottoDatabase
import java.io.File

internal class SavedOrationPlayback(
    private val db: SottoDatabase,
    private val root: File,
    private var output: PlaybackOutput?,
    private val facts: StateFlow<NarrationFacts>,
    private val persistence: PlaybackPersistence,
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val playerDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val clockDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : OrationPlayback {
    private val scope = CoroutineScope(SupervisorJob() + playerDispatcher)
    private var closed: Deferred<Unit>? = null
    private var requestVersion = 0L
    private var pendingExplicit: String? = null
    private var selected: SavedAudio? = null
    private var refreshing = false
    private var sleepVersion = 0L
    private var sleepJob: Job? = null

    private data class Resolved(val audio: SavedAudio, val positionMs: Long)

    init {
        scope.launch {
            while (isActive) {
                withContext(clockDispatcher) { delay(2000) }
                tick()
            }
        }
    }

    override fun play(id: String) {
        if (output == null) return
        pendingExplicit = id
        select(id)
    }

    private fun select(id: String, savedRow: OrationEntity? = null) {
        val version = ++requestVersion
        if (id in facts.value.deletingIds) { pendingExplicit = null; return }
        val epoch = persistence.epoch(id)
        scope.launch {
            val saved = resolve { (savedRow ?: db.orations().byId(id))?.let { playable(it) } }
            if (version != requestVersion || output == null) return@launch
            pendingExplicit = null
            if (saved == null || epoch != persistence.epoch(id) || id in facts.value.deletingIds) return@launch
            val player = output ?: return@launch
            val current = player.snapshot()
            observe(current)
            player.replace(saved.audio, persistence.resumePosition(id, saved.positionMs), current.speed, true)
            selected = saved.audio
        }
    }

    private fun playable(row: OrationEntity): Resolved? {
        val file = File(root, "${row.id}/audio.aac")
        if (!row.downloaded || row.audioBytes <= 0 || !file.isFile || file.length() < row.audioBytes) return null
        return Resolved(SavedAudio(row.id, row.title, file, row.audioBytes), row.playheadMs)
    }

    private suspend fun resolve(read: () -> Resolved?): Resolved? = try {
        withContext(storageDispatcher) { read() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Log.e("OrationPlayback", "Saved audio lookup failed", failure)
        null
    }

    private fun observe(snapshot: PlaybackSnapshot? = output?.snapshot()) {
        if (snapshot == null) return
        if (snapshot.id == selected?.id && snapshot.id !in facts.value.deletingIds) persistence.observe(snapshot)
    }

    private fun tick() {
        val snapshot = output?.snapshot() ?: return
        observe(snapshot)
        val audio = selected ?: return
        if (!snapshot.ended || snapshot.id != audio.id || refreshing || audio.id in facts.value.deletingIds) return
        val version = requestVersion
        val epoch = persistence.epoch(audio.id)
        refreshing = true
        scope.launch {
            try {
                val saved = resolve { db.orations().byId(audio.id)?.let { playable(it) } } ?: return@launch
                val current = output?.snapshot() ?: return@launch
                if (version != requestVersion || selected != audio || epoch != persistence.epoch(audio.id) ||
                    audio.id in facts.value.deletingIds || current.id != audio.id || !current.ended ||
                    current.positionMs != snapshot.positionMs || saved.audio.durableBytes <= audio.durableBytes) return@launch
                // Transport can change during storage work. Accept its current
                // intent/speed, and never undo a seek away from this frontier.
                output?.replace(saved.audio, current.positionMs, current.speed, current.playWhenReady)
                selected = saved.audio
            } finally {
                refreshing = false
            }
        }
    }

    override fun listenNow(row: OrationEntity) {
        if (output != null && selected == null && pendingExplicit == null) select(row.id, row)
    }

    override fun stopForDeletion(id: String) {
        val player = output ?: return
        persistence.invalidate(id)
        if (pendingExplicit == id) {
            ++requestVersion
            pendingExplicit = null
        }
        if (selected?.id == id) {
            selected = null
            player.stopAndClear()
        }
    }

    override fun sleep(minutes: Int) {
        if (output == null) return
        val version = ++sleepVersion
        sleepJob?.cancel()
        sleepJob = null
        if (minutes <= 0) return
        sleepJob = scope.launch {
            withContext(clockDispatcher) { delay(minutes * 60_000L) }
            if (version != sleepVersion) return@launch
            val player = output ?: return@launch
            player.pause()
            persistence.sleepAccepted(minutes)
        }
    }

    override fun close(): Deferred<Unit> {
        closed?.let { return it }
        observe()
        output = null
        selected = null
        pendingExplicit = null
        scope.cancel()
        return persistence.drain().also { closed = it }
    }
}
