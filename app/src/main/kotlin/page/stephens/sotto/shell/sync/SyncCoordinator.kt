package page.stephens.sotto.shell.sync

import android.content.Context
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow
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

/** Server-side OpenAI narration lifecycle owned by [SyncCoordinator] across Activity recreation. */
sealed interface ServerNarrationState {
    data object Idle : ServerNarrationState
    data class Submitting(val requestId: String, val title: String) : ServerNarrationState
    data class Polling(val requestId: String, val jobId: String, val title: String, val pct: Int) : ServerNarrationState
    data class Completed(val requestId: String, val name: String, val syncError: String? = null) : ServerNarrationState
    data class Failed(val requestId: String, val message: String) : ServerNarrationState
}

/** Reactive snapshot of opt-in sync session, reconciliation, transfers, and server narration. */
data class SyncState(
    val initialized: Boolean = false,
    val accountEmail: String? = null,
    val serverUrl: String? = null,
    val capabilities: ServerAccount? = null,
    val syncingLibrary: Boolean = false,
    val activeDownloads: Map<String, Int> = emptyMap(),
    val activeUploads: Set<String> = emptySet(),
    val authBusy: Boolean = false,
    val authStatus: String? = null,
    val accountSyncStatus: String? = null,
    val serverNarration: ServerNarrationState = ServerNarrationState.Idle,
)

/** Process-scoped sync boundary; `null` in the `local` flavor, retained in the `sync` flavor. */
interface SyncCoordinator {
    val state: StateFlow<SyncState>

    suspend fun awaitInitialized()
    suspend fun connect(serverUrl: String, email: String, password: String): Result<String>
    suspend fun disconnect(): String
    suspend fun syncNow(): Result<String>
    suspend fun download(oration: OrationEntity): Result<Unit>
    suspend fun upload(oration: OrationEntity): Result<String>
    suspend fun refreshAccount(force: Boolean = false): Result<ServerAccount>
    fun narrateOnServer(text: String, voice: String, title: String): String
    fun acknowledgeServerNarration(requestId: String)
    fun shutdown(): Deferred<Unit>
}

/** Provides the process-wide [SyncCoordinator] (or `null` in `local`). */
internal object SyncCoordinatorProvider {
    @Volatile private var instance: SyncCoordinator? = null
    @Volatile private var initialized = false

    fun get(context: Context): SyncCoordinator? {
        if (initialized) return instance
        return synchronized(this) {
            if (!initialized) {
                instance = SyncFactory.create(context.applicationContext)
                initialized = true
            }
            instance
        }
    }

    internal fun setForTest(coordinator: SyncCoordinator?): Deferred<Unit>? = synchronized(this) {
        val previous = instance
        instance = coordinator
        initialized = true
        previous?.shutdown()
    }

    internal fun shutdownForTest(): Deferred<Unit>? = synchronized(this) {
        val previous = instance
        instance = null
        initialized = false
        previous?.shutdown()
    }
}
