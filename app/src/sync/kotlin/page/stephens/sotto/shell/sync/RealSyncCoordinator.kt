package page.stephens.sotto.shell.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import page.stephens.sotto.engine.narrate.NarrationFacts
import page.stephens.sotto.engine.store.JobLog
import page.stephens.sotto.engine.store.DownloadPreparation
import page.stephens.sotto.engine.store.DownloadPublication
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.OrationPublication
import page.stephens.sotto.engine.store.SottoDatabase
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal class RealSyncCoordinator(
    private val db: SottoDatabase,
    private val filesDir: File,
    private val api: RemoteServer,
    private val credentials: CredentialStore,
    private val narrationFacts: StateFlow<NarrationFacts>,
    private val publication: OrationPublication,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val pollIntervalMs: Long = 2_500L,
    private val maxPollFailures: Int = 5,
) : SyncCoordinator {

    private data class Session(
        val epoch: Long,
        val serverUrl: String,
        val email: String,
        val token: String,
        val job: CompletableJob,
        val scope: CoroutineScope,
    )

    private data class InFlightSync(
        val epoch: Long,
        val deferred: Deferred<Result<String>>,
    )

    private data class ActiveDownload(
        val epoch: Long,
        val job: Job,
        val deferred: Deferred<Result<Unit>>,
    )

    private data class ActiveUpload(
        val epoch: Long,
        val job: Job,
        val candidateNames: Set<String>,
        val deferred: Deferred<Result<String>>,
        @Volatile var localCommitted: Boolean = false,
    )

    private data class RenamePush(
        val orationId: String,
        val serverName: String,
        val title: String,
        val previousMtime: Long,
    )

    private val coordinatorJob = SupervisorJob()
    private val scope = CoroutineScope(coordinatorJob + ioDispatcher)
    private val coordinatorLock = Any()
    private val authMutex = Mutex()
    private val credentialMutex = Mutex()
    private val initDone = CompletableDeferred<Unit>()

    private var nextEpoch = 0L
    private var touchedAuth = false
    private var currentSession: Session? = null
    private var activeConnectJob: Job? = null
    private var activeSync: InFlightSync? = null
    private var activeNarrationJob: Job? = null
    private val activeDownloads = mutableMapOf<String, ActiveDownload>()
    private val downloadPct = mutableMapOf<String, Int>()
    private val activeUploads = mutableMapOf<String, ActiveUpload>()

    private val _state = MutableStateFlow(SyncState())
    override val state: StateFlow<SyncState> = _state.asStateFlow()

    init {
        scope.launch {
            try {
                sweepAbandonedStaging()
                credentialMutex.withLock {
                    if (!touchedAuth) {
                        val stored = runCatching { credentials.load() }.getOrNull()
                        if (stored != null) {
                            installSessionLocked(stored.serverUrl, stored.email, stored.token)
                            _state.update {
                                it.copy(
                                    initialized = true,
                                    accountEmail = stored.email,
                                    serverUrl = stored.serverUrl,
                                )
                            }
                        } else {
                            _state.update { it.copy(initialized = true) }
                        }
                    } else {
                        _state.update { it.copy(initialized = true) }
                    }
                }
            } finally {
                initDone.complete(Unit)
            }
        }
        scope.launch {
            narrationFacts
                .map { it.deletingIds }
                .distinctUntilChanged()
                .collect { deleting -> cancelTransfersForDeleted(deleting) }
        }
    }

    override suspend fun awaitInitialized() {
        initDone.await()
    }

    override suspend fun connect(serverUrl: String, email: String, password: String): Result<String> {
        initDone.await()
        val retained = scope.async {
            authMutex.withLock {
                val job = coroutineContext[Job]
                synchronized(coordinatorLock) { activeConnectJob = job }
                _state.update { it.copy(authBusy = true) }
                try {
                    val cleanEmail = email.trim()
                    require(cleanEmail.isNotBlank()) { "Email is required" }
                    val normalizedServer = normalizeServerUrl(serverUrl)
                    val token = api.requestToken(normalizedServer, cleanEmail, password, CLIENT_LABEL)

                    val previousSession: Session?
                    credentialMutex.withLock {
                        touchedAuth = true
                        credentials.save(SyncCredentials(normalizedServer, cleanEmail, token))
                        previousSession = installSessionLocked(normalizedServer, cleanEmail, token)
                        _state.update {
                            it.copy(
                                accountEmail = cleanEmail,
                                serverUrl = normalizedServer,
                                capabilities = null,
                                syncingLibrary = false,
                                activeDownloads = emptyMap(),
                                activeUploads = emptySet(),
                                authBusy = false,
                                authStatus = null,
                                accountSyncStatus = null,
                                serverNarration = ServerNarrationState.Idle,
                            )
                        }
                    }
                    previousSession?.job?.cancelAndJoin()
                    runCatching { JobLog(db.joblog(), clock).log("sync-connect", cleanEmail) }
                    Result.success("Connected as $cleanEmail")
                } catch (c: CancellationException) {
                    _state.update { it.copy(authBusy = false) }
                    throw c
                } catch (t: Throwable) {
                    val msg = "Could not connect: ${t.message}"
                    _state.update { it.copy(authBusy = false, authStatus = msg) }
                    Result.failure(t)
                } finally {
                    synchronized(coordinatorLock) {
                        if (activeConnectJob === job) activeConnectJob = null
                    }
                }
            }
        }
        return retained.await()
    }

    override suspend fun disconnect(): String {
        initDone.await()
        val retained = scope.async {
            synchronized(coordinatorLock) { activeConnectJob?.cancel() }
            authMutex.withLock {
                val oldSession: Session?
                credentialMutex.withLock {
                    touchedAuth = true
                    oldSession = clearSessionLocked(
                        authBusy = true,
                        authStatus = _state.value.authStatus,
                        narrationState = ServerNarrationState.Idle,
                    )
                    credentials.clear()
                }
                oldSession?.job?.cancelAndJoin()
                runCatching { JobLog(db.joblog(), clock).log("sync-disconnect", "") }
                _state.update {
                    it.copy(
                        authBusy = false,
                        authStatus = "Disconnected.",
                        accountSyncStatus = null,
                        serverNarration = ServerNarrationState.Idle,
                    )
                }
                "Disconnected."
            }
        }
        return retained.await()
    }

    override suspend fun syncNow(): Result<String> {
        initDone.await()
        val deferred = synchronized(coordinatorLock) {
            val session = currentSession
                ?: return Result.failure(IllegalStateException("Not connected"))
            val existing = activeSync
            if (existing != null && existing.epoch == session.epoch && existing.deferred.isActive) {
                existing.deferred
            } else {
                val created = CompletableDeferred<Result<String>>()
                val job = session.scope.launch {
                    val res = runReconciliation(session)
                    created.complete(res)
                }
                job.invokeOnCompletion { err ->
                    if (!created.isCompleted) {
                        created.complete(Result.failure(err ?: CancellationException("Sync cancelled")))
                    }
                }
                activeSync = InFlightSync(session.epoch, created)
                created
            }
        }
        return deferred.await()
    }

    override suspend fun download(oration: OrationEntity): Result<Unit> {
        initDone.await()
        val remoteName = oration.serverName
            ?: return Result.failure(IllegalArgumentException("not a server entry"))
        val deferred = synchronized(coordinatorLock) {
            val session = currentSession
                ?: return Result.failure(IllegalStateException("Not connected"))
            if (oration.id in narrationFacts.value.deletingIds) {
                return Result.failure(CancellationException("Oration is being deleted"))
            }
            val existing = activeDownloads[oration.id]
            if (existing != null && existing.epoch == session.epoch && existing.deferred.isActive) {
                existing.deferred
            } else {
                val created = CompletableDeferred<Result<Unit>>()
                val job = session.scope.launch {
                    val res = runDownload(session, oration, remoteName)
                    created.complete(res)
                }
                val entry = ActiveDownload(session.epoch, job, created)
                activeDownloads[oration.id] = entry
                downloadPct[oration.id] = 0
                publishTransferStateLocked()
                job.invokeOnCompletion { err ->
                    synchronized(coordinatorLock) {
                        if (activeDownloads[oration.id] === entry) {
                            activeDownloads.remove(oration.id)
                            downloadPct.remove(oration.id)
                            publishTransferStateLocked()
                        }
                    }
                    if (!created.isCompleted) {
                        created.complete(Result.failure(err ?: CancellationException("Download cancelled")))
                    }
                }
                created
            }
        }
        return deferred.await()
    }

    override suspend fun upload(oration: OrationEntity): Result<String> {
        initDone.await()
        val slug = slugifyTitle(oration.title)
        val primaryName = "$slug.aac"
        val altName = "$slug-${oration.id.take(6)}.aac"

        val deferred = synchronized(coordinatorLock) {
            val session = currentSession
                ?: return Result.failure(IllegalStateException("Not connected"))
            if (oration.id in narrationFacts.value.deletingIds) {
                return Result.failure(CancellationException("Oration is being deleted"))
            }
            val existing = activeUploads[oration.id]
            if (existing != null && existing.epoch == session.epoch && existing.deferred.isActive) {
                existing.deferred
            } else {
                val created = CompletableDeferred<Result<String>>()
                val entryJob = Job(session.scope.coroutineContext[Job])
                val entry = ActiveUpload(session.epoch, entryJob, setOf(primaryName, altName), created)
                activeUploads[oration.id] = entry
                publishTransferStateLocked()

                session.scope.launch(entryJob) {
                    val res = runUpload(session, oration, primaryName, altName)
                    created.complete(res)
                }.invokeOnCompletion { err ->
                    entryJob.complete()
                    synchronized(coordinatorLock) {
                        if (activeUploads[oration.id] === entry) {
                            activeUploads.remove(oration.id)
                            publishTransferStateLocked()
                        }
                    }
                    if (!created.isCompleted) {
                        created.complete(Result.failure(err ?: CancellationException("Upload cancelled")))
                    }
                }
                created
            }
        }
        return deferred.await()
    }

    override suspend fun refreshAccount(force: Boolean): Result<ServerAccount> {
        initDone.await()
        val session = synchronized(coordinatorLock) { currentSession }
            ?: return Result.failure(IllegalStateException("Not connected"))
        val cached = _state.value.capabilities
        if (!force && cached != null) {
            return Result.success(cached)
        }
        val retained = session.scope.async {
            try {
                val account = api.account(session.serverUrl, session.token)
                synchronized(coordinatorLock) {
                    if (currentSession?.epoch == session.epoch) {
                        _state.update { it.copy(capabilities = account) }
                    }
                }
                Result.success(account)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (t is UnauthorizedApiException) {
                    expireSessionIfEpochMatches(session.epoch, narrationMessage = null)
                }
                Result.failure(t)
            }
        }
        return retained.await()
    }

    override fun narrateOnServer(text: String, voice: String, title: String): String {
        val requestId = idGenerator()
        val toLaunch: Pair<Session, Job?>? = synchronized(coordinatorLock) {
            val previousJob = activeNarrationJob
            val current = currentSession
            if (current == null) {
                activeNarrationJob = null
                _state.update { it.copy(serverNarration = ServerNarrationState.Failed(requestId, "Not connected")) }
                null
            } else {
                activeNarrationJob = null
                _state.update { it.copy(serverNarration = ServerNarrationState.Submitting(requestId, title)) }
                current to previousJob
            }
        }
        if (toLaunch != null) {
            val (session, previousJob) = toLaunch
            previousJob?.cancel(CancellationException("Replaced by newer server narration request"))
            val launched = session.scope.launch {
                runServerNarration(session, requestId, text, voice, title)
            }
            synchronized(coordinatorLock) {
                if (currentSession?.epoch == session.epoch &&
                    _state.value.serverNarration.requestIdOrNull == requestId &&
                    launched.isActive
                ) {
                    activeNarrationJob = launched
                }
            }
            launched.invokeOnCompletion {
                synchronized(coordinatorLock) {
                    if (activeNarrationJob === launched) {
                        activeNarrationJob = null
                    }
                }
            }
        }
        return requestId
    }

    override fun acknowledgeServerNarration(requestId: String) {
        synchronized(coordinatorLock) {
            val current = _state.value.serverNarration
            if ((current is ServerNarrationState.Completed || current is ServerNarrationState.Failed) &&
                current.requestIdOrNull == requestId
            ) {
                _state.update { it.copy(serverNarration = ServerNarrationState.Idle) }
            }
        }
    }

    override fun shutdown(): Deferred<Unit> {
        coordinatorJob.cancel()
        val done = CompletableDeferred<Unit>()
        coordinatorJob.invokeOnCompletion { done.complete(Unit) }
        return done
    }

    // --- Execution paths --------------------------------------------------

    private suspend fun runReconciliation(session: Session): Result<String> {
        checkSessionActive(session)
        updateStateIfSessionActive(session) { it.copy(syncingLibrary = true) }
        try {
            val entries = api.library(session.serverUrl, session.token)
            val dao = db.orations()
            val pendingPushes = mutableListOf<RenamePush>()
            var newCount = 0
            var renamedLocal = 0
            var renamedRemote = 0

            val activeUploadTargets = synchronized(coordinatorLock) {
                activeUploads.values
                    .filter { it.epoch == session.epoch && !it.localCommitted }
                    .flatMap { it.candidateNames }
                    .toSet()
            }

            db.runInTransaction {
                checkSessionActive(session)
                for (e in entries) {
                    val row = dao.byServerName(e.name)
                    if (row == null) {
                        if (e.name in activeUploadTargets) continue
                        dao.insert(
                            OrationEntity(
                                id = idGenerator(),
                                title = e.title,
                                status = "COMPLETE",
                                voiceId = e.voice ?: "af_heart",
                                words = e.words ?: 0,
                                chunksTotal = null,
                                audioBytes = e.size,
                                audioDurationMs = (e.durationSecs ?: 0) * 1000,
                                narratedMs = (e.secs ?: 0) * 1000,
                                createdAt = e.mtime * 1000,
                                updatedAt = e.mtime * 1000,
                                serverName = e.name,
                                serverMtime = e.mtime,
                                downloaded = false,
                            ),
                        )
                        newCount++
                    } else if (e.title != row.title) {
                        if (e.mtime > (row.serverMtime ?: 0) && e.mtime * 1000 >= row.updatedAt) {
                            dao.rename(row.id, e.title, e.mtime * 1000)
                            dao.markSynced(row.id, e.name, e.mtime)
                            JobLog(db.joblog(), clock).log("renamed", e.title, row.id, "server")
                            renamedLocal++
                        } else {
                            pendingPushes.add(RenamePush(row.id, e.name, row.title, e.mtime))
                        }
                    } else {
                        dao.markSynced(row.id, e.name, e.mtime)
                    }
                }
            }

            for (push in pendingPushes) {
                checkSessionActive(session)
                api.rename(session.serverUrl, session.token, push.serverName, push.title)
                dao.markSynced(push.orationId, push.serverName, push.previousMtime)
                renamedRemote++
            }

            if (renamedRemote > 0) {
                refreshServerMtimesBestEffort(session)
            }

            JobLog(db.joblog(), clock).log(
                "sync-pull",
                "",
                detail = "$newCount new, $renamedLocal/$renamedRemote renames",
            )
            val summary =
                "Synced: $newCount new from server, $renamedLocal renamed here, $renamedRemote renamed there"
            updateStateIfSessionActive(session) { it.copy(syncingLibrary = false, accountSyncStatus = summary) }
            return Result.success(summary)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            if (t is UnauthorizedApiException) {
                expireSessionIfEpochMatches(session.epoch, narrationMessage = null)
            }
            val msg = "Sync failed: ${t.message}"
            if (t !is UnauthorizedApiException) {
                updateStateIfSessionActive(session) { it.copy(syncingLibrary = false, accountSyncStatus = msg) }
                runCatching { JobLog(db.joblog(), clock).log("error", "sync", detail = t.message ?: t.toString()) }
            }
            return Result.failure(t)
        } finally {
            synchronized(coordinatorLock) {
                if (activeSync?.epoch == session.epoch) {
                    activeSync = null
                }
                if (currentSession?.epoch == session.epoch) {
                    _state.update { it.copy(syncingLibrary = false) }
                }
            }
        }
    }

    private suspend fun refreshServerMtimesBestEffort(session: Session) {
        try {
            val entries = api.library(session.serverUrl, session.token)
            val dao = db.orations()
            db.runInTransaction {
                checkSessionActive(session)
                for (e in entries) {
                    val row = dao.byServerName(e.name) ?: continue
                    if (row.title == e.title) {
                        dao.markSynced(row.id, e.name, e.mtime)
                    }
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (u: UnauthorizedApiException) {
            expireSessionIfEpochMatches(session.epoch, narrationMessage = null)
        } catch (_: Throwable) {
            // Follow-up refresh after an already-completed push/upload is strictly best-effort.
        }
    }

    private suspend fun runDownload(
        session: Session,
        oration: OrationEntity,
        remoteName: String,
    ): Result<Unit> {
        val stagingDir = File(filesDir, "$STAGING_DIR_NAME/${oration.id}-${idGenerator()}")
        return try {
            val attempt = when (val prepared = publication.prepareDownload(oration.id, remoteName)) {
                is DownloadPreparation.Ready -> prepared.attempt
                DownloadPreparation.AlreadyAvailable -> return Result.success(Unit)
                is DownloadPreparation.Rejected -> throw CancellationException("Download rejected: ${prepared.reason}")
                is DownloadPreparation.StorageFailure -> throw prepared.cause
            }
            val total = oration.audioBytes.coerceAtLeast(1L)
            val stagedAudio = File(stagingDir, "audio.aac")
            val stagedText = File(stagingDir, "source.md")
            var lastPct = 0

            api.downloadAudio(session.serverUrl, session.token, remoteName, stagedAudio) { read ->
                val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                if (pct != lastPct) {
                    lastPct = pct
                    synchronized(coordinatorLock) {
                        if (activeDownloads[oration.id]?.epoch == session.epoch) {
                            downloadPct[oration.id] = pct
                            publishTransferStateLocked()
                        }
                    }
                }
            }
            val hasText = api.downloadText(session.serverUrl, session.token, remoteName, stagedText)

            val transferJob = coroutineContext[Job]
            val result = publication.publishDownload(attempt, stagedAudio, stagedText.takeIf { hasText }) {
                transferJob?.isActive == true && oration.id !in narrationFacts.value.deletingIds &&
                    synchronized(coordinatorLock) { currentSession?.epoch == session.epoch }
            }
            when (result) {
                DownloadPublication.Published, DownloadPublication.AlreadyAvailable -> Result.success(Unit)
                is DownloadPublication.Rejected -> throw CancellationException("Download rejected: ${result.reason}")
                is DownloadPublication.StorageFailure -> throw result.cause
            }
        } catch (t: Throwable) {
            if (t is UnauthorizedApiException) {
                expireSessionIfEpochMatches(session.epoch, narrationMessage = null)
            } else if (t !is CancellationException) {
                runCatching { JobLog(db.joblog(), clock).log("error", oration.title, oration.id, "download: $t") }
            }
            if (t is CancellationException) throw t
            Result.failure(t)
        } finally {
            runCatching { stagingDir.deleteRecursively() }
        }
    }

    private suspend fun runUpload(
        session: Session,
        oration: OrationEntity,
        primaryName: String,
        altName: String,
    ): Result<String> {
        val audio = File(filesDir, "orations/${oration.id}/audio.aac")
        if (!audio.exists()) {
            return Result.failure(IllegalArgumentException("no audio to upload"))
        }
        return try {
            val text = File(filesDir, "orations/${oration.id}/source.md").takeIf { it.exists() }
            val meta = JSONObject()
                .put("title", oration.title)
                .put("words", oration.words)
                .put("secs", oration.narratedMs / 1000)
                .put("voice", oration.voiceId)
                .put("duration_secs", audio.length() * 8 / 64_000)
                .toString()

            val finalName = try {
                api.upload(session.serverUrl, session.token, primaryName, audio, text, meta, ifNoneMatch = true)
                primaryName
            } catch (e: ApiException) {
                if (e.code != 409) throw e
                api.upload(session.serverUrl, session.token, altName, audio, text, meta, ifNoneMatch = false)
                altName
            }

            var committedRows = 0
            db.runInTransaction {
                checkSessionActive(session)
                if (oration.id in narrationFacts.value.deletingIds) {
                    throw CancellationException("Oration deleted during upload")
                }
                committedRows = db.orations().markSynced(oration.id, finalName, clock() / 1000)
                if (committedRows > 0) {
                    JobLog(db.joblog(), clock).log("uploaded", oration.title, oration.id, finalName)
                }
            }
            if (committedRows == 0) {
                throw CancellationException("Oration missing after upload")
            }

            // Mark candidate target names committed so follow-up refresh matches the updated row
            // while keeping the upload in activeUploads until refresh finishes.
            synchronized(coordinatorLock) {
                activeUploads[oration.id]?.takeIf { it.epoch == session.epoch }?.localCommitted = true
            }
            refreshServerMtimesBestEffort(session)
            Result.success("Uploaded as $finalName")
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            if (t is UnauthorizedApiException) {
                expireSessionIfEpochMatches(session.epoch, narrationMessage = null)
            } else {
                runCatching { JobLog(db.joblog(), clock).log("error", oration.title, oration.id, "upload: $t") }
            }
            Result.failure(t)
        }
    }

    private suspend fun runServerNarration(
        session: Session,
        requestId: String,
        text: String,
        voice: String,
        title: String,
    ) {
        fun updateNarration(
            allowAfterExpire: Boolean = false,
            transform: (ServerNarrationState) -> ServerNarrationState?,
        ) {
            synchronized(coordinatorLock) {
                if (!allowAfterExpire && currentSession?.epoch != session.epoch) return
                val cur = _state.value.serverNarration
                val matches = (cur is ServerNarrationState.Submitting || cur is ServerNarrationState.Polling) &&
                    cur.requestIdOrNull == requestId
                if (matches) {
                    val next = transform(cur) ?: return
                    _state.update { it.copy(serverNarration = next) }
                }
            }
        }

        fun failLoudly(message: String, logError: Boolean = true) {
            if (logError) {
                runCatching { JobLog(db.joblog(), clock).log("error", title, detail = "server-narrate: $message") }
            }
            updateNarration(allowAfterExpire = true) { ServerNarrationState.Failed(requestId, message) }
        }

        try {
            val jobId = api.narrate(session.serverUrl, session.token, text, voice, title)
            runCatching { JobLog(db.joblog(), clock).log("server-narrate", title, detail = "voice=$voice job=$jobId") }
            updateNarration { ServerNarrationState.Polling(requestId, jobId, title, pct = 0) }

            var consecutiveFailures = 0
            while (coroutineContext.isActive) {
                delay(pollIntervalMs)
                val job = try {
                    api.jobStatus(session.serverUrl, session.token, jobId)
                } catch (err: Throwable) {
                    if (err is CancellationException) throw err
                    if (err is UnauthorizedApiException) {
                        expireSessionIfEpochMatches(session.epoch, narrationMessage = SESSION_EXPIRED_MESSAGE)
                        failLoudly(SESSION_EXPIRED_MESSAGE, logError = false)
                        return
                    }
                    if (err is ApiException && err.code == 404) {
                        failLoudly("Server narration no longer exists (404)")
                        return
                    }
                    consecutiveFailures++
                    if (consecutiveFailures >= maxPollFailures) {
                        failLoudly("Could not reach server (${err.message ?: "poll error"})")
                        return
                    }
                    continue
                }

                consecutiveFailures = 0
                when {
                    job.status == "stopped" -> {
                        failLoudly(job.error ?: "Server narration stopped")
                        return
                    }
                    job.status == "error" || job.error != null -> {
                        failLoudly(job.error ?: "Server narration failed")
                        return
                    }
                    job.status == "done" -> {
                        val name = job.name?.takeIf { it.isNotBlank() }
                        if (name == null) {
                            failLoudly("Server narration finished without a library entry")
                            return
                        }
                        val syncRes = syncNow()
                        updateNarration {
                            ServerNarrationState.Completed(
                                requestId = requestId,
                                name = name,
                                syncError = syncRes.exceptionOrNull()?.let { it.message ?: it.toString() },
                            )
                        }
                        return
                    }
                    job.status == "queued" || job.status == "running" -> {
                        updateNarration { cur ->
                            (cur as? ServerNarrationState.Polling)?.copy(pct = job.pct.coerceIn(0, 100))
                        }
                    }
                    else -> {
                        failLoudly("Unexpected server narration status: ${job.status}")
                        return
                    }
                }
            }
        } catch (c: CancellationException) {
            updateNarration(allowAfterExpire = true) {
                if (_state.value.authStatus == SESSION_EXPIRED_MESSAGE) {
                    ServerNarrationState.Failed(requestId, SESSION_EXPIRED_MESSAGE)
                } else {
                    null
                }
            }
            throw c
        } catch (u: UnauthorizedApiException) {
            expireSessionIfEpochMatches(session.epoch, narrationMessage = SESSION_EXPIRED_MESSAGE)
            failLoudly(SESSION_EXPIRED_MESSAGE, logError = false)
        } catch (t: Throwable) {
            failLoudly(t.message ?: "could not submit")
        }
    }

    // --- Session & staging housekeeping ----------------------------------

    private inline fun updateStateIfSessionActive(
        session: Session,
        crossinline transform: (SyncState) -> SyncState,
    ) {
        synchronized(coordinatorLock) {
            if (currentSession?.epoch == session.epoch) {
                _state.update { transform(it) }
            }
        }
    }

    private fun installSessionLocked(serverUrl: String, email: String, token: String): Session? =
        synchronized(coordinatorLock) {
            val old = currentSession
            val epoch = ++nextEpoch
            val sJob = SupervisorJob(coordinatorJob)
            val sScope = CoroutineScope(sJob + ioDispatcher)
            currentSession = Session(epoch, serverUrl, email, token, sJob, sScope)
            activeSync = null
            activeNarrationJob = null
            activeDownloads.clear()
            downloadPct.clear()
            activeUploads.clear()
            old
        }

    private fun clearSessionLocked(
        authBusy: Boolean,
        authStatus: String?,
        narrationState: ServerNarrationState?,
    ): Session? = synchronized(coordinatorLock) {
        val old = currentSession
        currentSession = null
        activeSync = null
        activeNarrationJob = null
        activeDownloads.clear()
        downloadPct.clear()
        activeUploads.clear()
        _state.update { cur ->
            cur.copy(
                accountEmail = null,
                serverUrl = null,
                capabilities = null,
                syncingLibrary = false,
                activeDownloads = emptyMap(),
                activeUploads = emptySet(),
                authBusy = authBusy,
                authStatus = authStatus,
                accountSyncStatus = null,
                serverNarration = narrationState ?: cur.serverNarration,
            )
        }
        old
    }

    private suspend fun expireSessionIfEpochMatches(failedEpoch: Long, narrationMessage: String?) {
        withContext(NonCancellable) {
            val expired: Session? = credentialMutex.withLock {
                val matched = synchronized(coordinatorLock) {
                    val cur = currentSession
                    if (cur == null || cur.epoch != failedEpoch) return@synchronized null
                    val nextNarration = when (val n = _state.value.serverNarration) {
                        is ServerNarrationState.Submitting ->
                            ServerNarrationState.Failed(n.requestId, narrationMessage ?: SESSION_EXPIRED_MESSAGE)
                        is ServerNarrationState.Polling ->
                            ServerNarrationState.Failed(n.requestId, narrationMessage ?: SESSION_EXPIRED_MESSAGE)
                        else -> null
                    }
                    clearSessionLocked(
                        authBusy = false,
                        authStatus = SESSION_EXPIRED_MESSAGE,
                        narrationState = nextNarration,
                    )
                }
                if (matched != null) {
                    credentials.clear()
                }
                matched
            }
            if (expired != null) {
                expired.job.cancel()
                runCatching { JobLog(db.joblog(), clock).log("error", "sync", detail = "401 unauthorized") }
            }
        }
    }

    private fun cancelTransfersForDeleted(deletingIds: Set<String>) {
        if (deletingIds.isEmpty()) return
        val jobsToCancel = synchronized(coordinatorLock) {
            buildList {
                for (id in deletingIds) {
                    activeDownloads[id]?.job?.let { add(it) }
                    activeUploads[id]?.job?.let { add(it) }
                }
            }
        }
        jobsToCancel.forEach { it.cancel(CancellationException("Oration deleted")) }
    }

    private fun checkSessionActive(session: Session) {
        val active = synchronized(coordinatorLock) { currentSession?.epoch == session.epoch }
        if (!active) throw CancellationException("Sync session superseded")
    }

    private fun publishTransferStateLocked() {
        val downloadsSnapshot = downloadPct.toMap()
        val uploadsSnapshot = activeUploads.keys.toSet()
        _state.update { it.copy(activeDownloads = downloadsSnapshot, activeUploads = uploadsSnapshot) }
    }

    private fun sweepAbandonedStaging() {
        val stagingRoot = File(filesDir, STAGING_DIR_NAME)
        if (!stagingRoot.isDirectory) return
        stagingRoot.listFiles()?.forEach { child ->
            runCatching { child.deleteRecursively() }
        }
    }

    companion object {
        private const val CLIENT_LABEL = "sotto-android"
        private const val STAGING_DIR_NAME = "sync-staging"
        private const val SESSION_EXPIRED_MESSAGE = "Session expired - reconnect your account."

        private fun normalizeServerUrl(raw: String): String {
            val trimmed = raw.trim().ifBlank { EncryptedCredentialStore.DEFAULT_SERVER }
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
        }

        private fun slugifyTitle(title: String): String =
            title.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(50)
                .ifBlank { "oration" }
    }
}
