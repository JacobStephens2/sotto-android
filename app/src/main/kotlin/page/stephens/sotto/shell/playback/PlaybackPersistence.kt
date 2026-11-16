package page.stephens.sotto.shell.playback

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import page.stephens.sotto.engine.store.JobLog
import page.stephens.sotto.engine.store.SottoDatabase
import java.util.concurrent.ConcurrentHashMap

// Shared by replacement owners. Messages contain no player, owner, or callback.
internal class PlaybackPersistence(
    private val db: SottoDatabase,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private sealed interface Write {
        data class Position(val id: String, val positionMs: Long, val deletionEpoch: Long) : Write
        data class Sleep(val minutes: Int) : Write
        data class Barrier(val completion: CompletableDeferred<Unit>) : Write
    }

    private val epochs = ConcurrentHashMap<String, Long>()
    private val latestPositions = ConcurrentHashMap<String, Write.Position>()
    private val queue = Channel<Write>(Channel.UNLIMITED)
    private val worker = CoroutineScope(SupervisorJob() + dispatcher).launch {
        for (write in queue) {
            try {
                when (write) {
                    is Write.Position -> db.runInTransaction {
                        // A transaction already past this check finishes before
                        // deletion can remove its row. Queued stale writes skip.
                        if (epoch(write.id) == write.deletionEpoch) {
                            db.orations().setPlayhead(write.id, write.positionMs)
                        }
                    }
                    is Write.Sleep -> JobLog(db.joblog()).log("sleep-timer", "paused after ${write.minutes} min")
                    is Write.Barrier -> write.completion.complete(Unit)
                }
            } catch (failure: Exception) {
                Log.e("OrationPlayback", "Playback persistence failed", failure)
            }
        }
    }

    fun epoch(id: String): Long = epochs[id] ?: 0L

    fun invalidate(id: String) {
        epochs.compute(id) { _, previous -> (previous ?: 0L) + 1 }
        latestPositions.remove(id)
    }

    fun observe(snapshot: PlaybackSnapshot) {
        val id = snapshot.id ?: return
        if (snapshot.positionMs > 0) {
            val position = Write.Position(id, snapshot.positionMs, epoch(id))
            latestPositions[id] = position
            enqueue(position)
        }
    }

    // A selection can arrive before Room receives an accepted switch/close
    // observation. Keep primitive observations available across owners, even
    // across a read racing the write's commit; deletion forgets the old position.
    fun resumePosition(id: String, savedPositionMs: Long): Long = latestPositions[id]
        ?.takeIf { it.deletionEpoch == epoch(id) }?.positionMs ?: savedPositionMs

    fun sleepAccepted(minutes: Int) { enqueue(Write.Sleep(minutes)) }

    fun drain(): Deferred<Unit> = CompletableDeferred<Unit>().also { enqueue(Write.Barrier(it)) }

    private fun enqueue(write: Write) { check(queue.trySend(write).isSuccess) }

    internal fun shutdownForTest(): kotlinx.coroutines.Job {
        queue.close()
        return worker
    }

    companion object {
        @Volatile private var shared: PlaybackPersistence? = null

        fun shared(db: SottoDatabase): PlaybackPersistence = shared ?: synchronized(this) {
            shared ?: PlaybackPersistence(db).also { shared = it }
        }

        internal fun shutdownSharedForTest(): kotlinx.coroutines.Job? = synchronized(this) {
            shared?.shutdownForTest().also { shared = null }
        }
    }
}
