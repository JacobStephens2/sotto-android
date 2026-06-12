package page.stephens.sotto.shell.sync

// The flavor seam (ADR-007). main defines the interface; each flavor supplies
// SyncFactory: `local` returns null (and contains no network code at all),
// `sync` returns the real provider. Narration never depends on any of this.

import android.content.Context
import page.stephens.sotto.engine.store.OrationEntity

interface SyncProvider {
    /** Connected account email, or null. */
    fun accountEmail(): String?
    suspend fun connect(serverUrl: String, email: String, password: String): Result<String>
    fun disconnect()
    /** Pull the server library; mirror new entries as not-yet-downloaded rows;
     *  reconcile renames last-write-wins. Returns a human summary. */
    suspend fun syncNow(): Result<String>
    suspend fun download(o: OrationEntity): Result<Unit>
    suspend fun upload(o: OrationEntity): Result<String>
}

object Sync {
    fun provider(context: Context): SyncProvider? = SyncFactory.create(context)
}
