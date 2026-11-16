package page.stephens.sotto.shell.playback

import kotlinx.coroutines.Deferred
import page.stephens.sotto.engine.store.OrationEntity

// Commands and close are accepted on the player thread. Close fences effects
// synchronously; only application-owned persistence may outlive it.
internal interface OrationPlayback {
    fun play(id: String)
    fun listenNow(row: OrationEntity)
    fun stopForDeletion(id: String)
    fun sleep(minutes: Int)
    fun close(): Deferred<Unit>
}
