package page.stephens.sotto.shell.sync

// The flavor seam (ADR-007). main defines the interface; each flavor supplies
// SyncFactory: `local` returns null (and contains no network code at all),
// `sync` returns the real provider. Narration never depends on any of this.

import android.content.Context
import page.stephens.sotto.engine.store.OrationEntity

/** What the account may do server-side (docs/12-api-contract.md, /api/v1/me).
 *  `openaiVoices` is non-empty only for accounts the server allows onto the
 *  paid OpenAI backend; everyone else narrates with Kokoro, on-device or server. */
data class ServerAccount(
    val email: String,
    val mayOpenai: Boolean,
    val openaiVoices: List<String>,
    val defaultVoice: String,
)

/** A server narration job in flight (POST /api/v1/narrate then poll). `name` is
 *  the Library filename, set only once the result has been saved server-side. */
data class ServerJob(val status: String, val pct: Int, val name: String?, val error: String?)

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
    /** The connected account's server-side capabilities. */
    suspend fun account(): Result<ServerAccount>
    /** Submit text for server-side narration (lets an allowed account use the
     *  paid OpenAI backend). Returns the job id to poll with [serverJobStatus]. */
    suspend fun narrateOnServer(text: String, voice: String, title: String): Result<String>
    suspend fun serverJobStatus(jobId: String): Result<ServerJob>
}

object Sync {
    fun provider(context: Context): SyncProvider? = SyncFactory.create(context)
}
