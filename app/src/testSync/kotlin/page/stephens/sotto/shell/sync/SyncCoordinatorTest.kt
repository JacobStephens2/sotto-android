package page.stephens.sotto.shell.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import page.stephens.sotto.engine.narrate.NarrationFacts
import page.stephens.sotto.engine.store.OrationPublication
import page.stephens.sotto.engine.store.DurableSourcePublisher
import page.stephens.sotto.engine.store.DurableStagedFilePublisher
import page.stephens.sotto.engine.store.StagedFilePublisher
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.SottoDatabase
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class SyncCoordinatorTest {

    private lateinit var db: SottoDatabase
    private lateinit var filesDir: File
    private lateinit var narrationFacts: MutableStateFlow<NarrationFacts>
    private lateinit var store: InMemoryCredentialStore
    private lateinit var server: FakeRemoteServer
    private val coordinators = mutableListOf<RealSyncCoordinator>()
    private var nowMs = 1_000_000L
    private val nextId = AtomicInteger(1)
    private lateinit var publication: OrationPublication

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            SottoDatabase::class.java,
        ).allowMainThreadQueries().build()
        filesDir = Files.createTempDirectory("sotto-sync-coord").toFile()
        publication = OrationPublication(db, File(filesDir, "orations"))
        narrationFacts = MutableStateFlow(NarrationFacts())
        store = InMemoryCredentialStore()
        server = FakeRemoteServer()
    }

    @After
    fun tearDown() {
        runBlocking {
            coordinators.forEach { it.shutdown().await() }
            coordinators.clear()
            db.close()
            filesDir.deleteRecursively()
        }
    }

    private fun buildCoordinator(
        pollIntervalMs: Long = 5L,
        maxPollFailures: Int = 3,
    ): RealSyncCoordinator = RealSyncCoordinator(
        db = db,
        filesDir = filesDir,
        api = server,
        credentials = store,
        narrationFacts = narrationFacts,
        publication = publication,
        ioDispatcher = Dispatchers.IO,
        clock = { nowMs },
        idGenerator = { "id-${nextId.getAndIncrement()}" },
        pollIntervalMs = pollIntervalMs,
        maxPollFailures = maxPollFailures,
    ).also { coordinators.add(it) }

    @Test
    fun `startup sweeps abandoned staging directories and loads saved credentials`() = runBlocking {
        val abandoned = File(filesDir, "sync-staging/old-attempt").apply { mkdirs() }
        File(abandoned, "audio.aac").writeText("partial")
        val keptOration = File(filesDir, "orations/existing").apply { mkdirs() }
        File(keptOration, "audio.aac").writeText("real")
        store.save(SyncCredentials("https://example.test", "saved@example.com", "tok-saved"))

        val coord = buildCoordinator()
        coord.awaitInitialized()

        assertFalse(abandoned.exists())
        assertTrue(File(keptOration, "audio.aac").exists())
        assertTrue(coord.state.value.initialized)
        assertEquals("saved@example.com", coord.state.value.accountEmail)
        assertEquals("https://example.test", coord.state.value.serverUrl)
    }

    @Test
    fun `connect normalizes URL and disconnect cancels active transfers off-main`() = runBlocking {
        val coord = buildCoordinator()
        coord.awaitInitialized()

        val res = coord.connect("sotto.local:8080", " user@example.com ", "pw")
        assertTrue(res.isSuccess)
        assertEquals("https://sotto.local:8080", coord.state.value.serverUrl)
        assertEquals("user@example.com", coord.state.value.accountEmail)
        assertEquals("tok-user@example.com", store.load()?.token)

        val row = OrationEntity(
            id = "dl-disconnect",
            title = "Long Read",
            status = "COMPLETE",
            voiceId = "af_heart",
            words = 10,
            chunksTotal = null,
            audioBytes = 100,
            createdAt = 1_000,
            updatedAt = 1_000,
            serverName = "long.aac",
            serverMtime = 10,
            downloaded = false,
        )
        db.orations().insert(row)

        val dlStarted = CompletableDeferred<Unit>()
        val dlCancelled = CompletableDeferred<Unit>()
        server.onDownloadAudio = { _, dest ->
            dest.parentFile?.mkdirs()
            dest.writeText("partial")
            dlStarted.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                dlCancelled.complete(Unit)
            }
        }

        val dlDeferred = async { coord.download(row) }
        dlStarted.await()
        assertEquals(mapOf("dl-disconnect" to 0), coord.state.value.activeDownloads)

        val msg = coord.disconnect()
        assertEquals("Disconnected.", msg)
        withTimeout(2_000) { dlCancelled.await() }
        val dlResult = dlDeferred.await()
        assertTrue(dlResult.isFailure)
        assertNull(store.load())
        assertNull(coord.state.value.accountEmail)
        assertTrue(coord.state.value.activeDownloads.isEmpty())
        assertFalse(File(filesDir, "orations/dl-disconnect/audio.aac").exists())
        assertTrue(db.joblog().recent().any { it.event == "sync-disconnect" })
    }

    @Test
    fun `active-epoch 401 clears credentials while stale-epoch 401 preserves newer session`() = runBlocking {
        val coord = buildCoordinator()
        coord.awaitInitialized()
        assertTrue(coord.connect("http://s1", "a@example.com", "pw1").isSuccess)

        val epoch1Started = CompletableDeferred<Unit>()
        val releaseEpoch1 = CompletableDeferred<Unit>()
        server.onLibrary = { token ->
            if (token == "tok-a@example.com") {
                epoch1Started.complete(Unit)
                releaseEpoch1.await()
                throw UnauthorizedApiException("revoked epoch 1")
            }
            listOf(RemoteEntry("new.aac", "New", 5, 1, "af_heart", 10, 1, 50, false))
        }

        val staleSync = async { coord.syncNow() }
        epoch1Started.await()

        // Disconnect and connect as newer epoch while epoch 1 request is still blocked.
        coord.disconnect()
        assertTrue(coord.connect("http://s2", "b@example.com", "pw2").isSuccess)
        releaseEpoch1.complete(Unit)

        assertTrue(staleSync.await().isFailure)
        // Newer session credentials must remain intact!
        assertEquals("b@example.com", coord.state.value.accountEmail)
        assertEquals("tok-b@example.com", store.load()?.token)

        // Now an active-epoch 401 must log out epoch 2 and clear credentials.
        server.onLibrary = { throw UnauthorizedApiException("revoked epoch 2") }
        val activeFail = coord.syncNow()
        assertTrue(activeFail.isFailure)
        assertNull(coord.state.value.accountEmail)
        assertNull(store.load())
        assertTrue(coord.state.value.authStatus.orEmpty().contains("expired", ignoreCase = true))
    }

    @Test
    fun `syncNow coalesces concurrent refreshes without duplicate serverName rows`() = runBlocking {
        val coord = buildCoordinator()
        coord.awaitInitialized()
        coord.connect("http://s", "a@example.com", "pw")

        val calls = AtomicInteger(0)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        server.onLibrary = {
            calls.incrementAndGet()
            entered.complete(Unit)
            release.await()
            listOf(RemoteEntry("essay.aac", "Essay", 100, 10, "af_heart", 2048, 20, 42, true))
        }

        val first = async { coord.syncNow() }
        entered.await()
        val concurrent = (1..3).map {
            async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { coord.syncNow() }
        }
        release.complete(Unit)

        val results = (listOf(first) + concurrent).awaitAll()
        assertEquals(1, calls.get())
        results.forEach { assertTrue(it.isSuccess) }
        assertEquals(1, db.orations().all().count { it.serverName == "essay.aac" })
    }

    @Test
    fun `remote rename pulls e_mtime_ms and logs renamed cleanly after setPlayhead updates`() = runBlocking {
        val coord = buildCoordinator()
        coord.awaitInitialized()
        coord.connect("http://s", "a@example.com", "pw")

        db.orations().insert(
            OrationEntity(
                id = "o-playhead",
                title = "Original",
                status = "COMPLETE",
                voiceId = "af_heart",
                words = 50,
                chunksTotal = null,
                audioBytes = 1024,
                createdAt = 100_000L,
                updatedAt = 100_000L,
                serverName = "orig.aac",
                serverMtime = 100L,
                downloaded = true,
            ),
        )
        // Frequent playhead saves must NOT overwrite updatedAt.
        db.orations().setPlayhead("o-playhead", 88_000L)
        server.libraryEntries = listOf(
            RemoteEntry("orig.aac", "Renamed Remotely", 50, 10, "af_heart", 1024, 10, 105L, false),
        )

        val syncRes = coord.syncNow()
        assertTrue(syncRes.isSuccess)
        val row = db.orations().byId("o-playhead")!!
        assertEquals("Renamed Remotely", row.title)
        assertEquals(105_000L, row.updatedAt)
        assertEquals(105L, row.serverMtime)
        assertEquals(88_000L, row.playheadMs)
        assertEquals(1, db.joblog().count("o-playhead", "renamed"))
    }

    @Test
    fun `local rename push and upload run best-effort refresh for authoritative serverMtime`() = runBlocking {
        val coord = buildCoordinator()
        coord.awaitInitialized()
        coord.connect("http://s", "a@example.com", "pw")

        // Part A: local rename push captures updated serverMtime via follow-up refresh
        db.orations().insert(
            OrationEntity(
                id = "o-push",
                title = "Phone Rename",
                status = "COMPLETE",
                voiceId = "af_heart",
                words = 50,
                chunksTotal = null,
                audioBytes = 1024,
                createdAt = 100_000L,
                updatedAt = 200_000L,
                serverName = "push.aac",
                serverMtime = 100L,
                downloaded = true,
            ),
        )
        var libCalls = 0
        server.onLibrary = {
            libCalls++
            if (libCalls == 1) {
                listOf(RemoteEntry("push.aac", "Old Server Title", 50, 10, "af_heart", 1024, 10, 100L, false))
            } else {
                listOf(RemoteEntry("push.aac", "Phone Rename", 50, 10, "af_heart", 1024, 10, 250L, false))
            }
        }
        assertTrue(coord.syncNow().isSuccess)
        assertEquals(listOf("push.aac" to "Phone Rename"), server.renames)
        assertEquals(250L, db.orations().byId("o-push")!!.serverMtime)

        // Part B: upload succeeds even when follow-up library refresh fails; when refresh succeeds, mtime updates
        val uploadRow = OrationEntity(
            id = "o-up",
            title = "Fresh Essay",
            status = "COMPLETE",
            voiceId = "af_heart",
            words = 20,
            chunksTotal = 1,
            chunksDone = 1,
            audioBytes = 500,
            createdAt = 300_000L,
            updatedAt = 300_000L,
            downloaded = true,
        )
        db.orations().insert(uploadRow)
        val upDir = File(filesDir, "orations/o-up").apply { mkdirs() }
        File(upDir, "audio.aac").writeBytes(byteArrayOf(1, 2, 3, 4))
        server.onLibrary = { throw IOException("transient refresh hiccup") }

        val upRes = coord.upload(uploadRow)
        assertTrue(upRes.isSuccess)
        val synced = db.orations().byId("o-up")!!
        assertEquals("fresh-essay.aac", synced.serverName)
        assertEquals(1, db.joblog().count("o-up", "uploaded"))
    }

    @Test
    fun `upload dedupes concurrent calls and prevents syncNow from creating a duplicate row`() = runBlocking {
        val coord = buildCoordinator()
        coord.awaitInitialized()
        coord.connect("http://s", "a@example.com", "pw")

        val row = OrationEntity(
            id = "o-race",
            title = "Race Essay",
            status = "COMPLETE",
            voiceId = "af_heart",
            words = 20,
            chunksTotal = 1,
            chunksDone = 1,
            audioBytes = 500,
            createdAt = 1_000L,
            updatedAt = 1_000L,
            downloaded = true,
        )
        db.orations().insert(row)
        File(filesDir, "orations/o-race").apply { mkdirs() }
        File(filesDir, "orations/o-race/audio.aac").writeBytes(byteArrayOf(7, 8, 9))

        val uploadEntered = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        val putCount = AtomicInteger(0)
        server.onUpload = { name ->
            putCount.incrementAndGet()
            uploadEntered.complete(Unit)
            releaseUpload.await()
            // Server already lists the uploaded item while upload() hasn't finished markSynced yet.
            server.libraryEntries = listOf(RemoteEntry(name, "Race Essay", 20, 1, "af_heart", 3, 1, 999L, false))
            true
        }
        server.onLibrary = { server.libraryEntries }

        val up1 = async { coord.upload(row) }
        uploadEntered.await()
        val up2 = async { coord.upload(row) }
        val concurrentSync = async { coord.syncNow() }
        assertTrue(concurrentSync.await().isSuccess)
        // Must NOT have inserted a duplicate server row while race-essay.aac was uploading.
        assertEquals(1, db.orations().all().size)

        releaseUpload.complete(Unit)
        assertTrue(up1.await().isSuccess)
        assertTrue(up2.await().isSuccess)
        assertEquals(1, putCount.get())
        assertEquals(1, db.orations().all().size)
        assertEquals("race-essay.aac", db.orations().byId("o-race")!!.serverName)
        assertEquals(999L, db.orations().byId("o-race")!!.serverMtime)
    }

    @Test
    fun `account replacement waits for old publication rollback and keeps newer successful bytes`() = runBlocking {
        val placed = CountDownLatch(1)
        val releasePlacement = CountDownLatch(1)
        val roomHeld = CountDownLatch(1)
        val releaseRoom = CountDownLatch(1)
        val promotions = AtomicInteger()
        publication = OrationPublication(db, File(filesDir, "orations"), DurableSourcePublisher,
            stagedFiles = object : StagedFilePublisher by DurableStagedFilePublisher {
                override fun promote(staged: File, target: File) {
                    DurableStagedFilePublisher.promote(staged, target)
                    if (target.name == "audio.aac" && promotions.incrementAndGet() == 1) {
                        placed.countDown()
                        check(releasePlacement.await(10, TimeUnit.SECONDS))
                    }
                }
            })
        val coord = buildCoordinator()
        coord.awaitInitialized()
        assertTrue(coord.connect("http://s", "old@example.com", "pw").isSuccess)
        val row = OrationEntity(
            "replacement", "Essay", "COMPLETE", "af_heart", 10, null,
            createdAt = 1, updatedAt = 2, serverName = "essay.aac", downloaded = false,
        )
        db.orations().insert(row)
        val transfers = AtomicInteger()
        server.onDownloadAudio = { _, dest ->
            dest.parentFile!!.mkdirs()
            dest.writeText(if (transfers.incrementAndGet() == 1) "old bytes" else "new bytes")
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val old = async(Dispatchers.Default) { coord.download(row) }
            assertTrue(placed.await(5, TimeUnit.SECONDS))
            assertEquals("old bytes", File(filesDir, "orations/replacement/audio.aac").readText())
            val held = pool.submit {
                db.runInTransaction {
                    roomHeld.countDown()
                    check(releaseRoom.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(roomHeld.await(5, TimeUnit.SECONDS))
            val reconnect = async(Dispatchers.Default) { coord.connect("http://s", "new@example.com", "pw") }
            withTimeout(5_000) { coord.state.first { it.accountEmail == "new@example.com" } }
            val newer = async(Dispatchers.Default) { coord.download(row) }
            withTimeout(5_000) { coord.state.first { "replacement" in it.activeDownloads } }
            assertFalse(newer.isCompleted)
            assertFalse(old.isCompleted)
            releasePlacement.countDown()
            releaseRoom.countDown()
            held.get(5, TimeUnit.SECONDS)
            assertTrue(withTimeout(5_000) { old.await() }.isFailure)
            assertTrue(withTimeout(5_000) { reconnect.await() }.isSuccess)
            assertTrue(withTimeout(5_000) { newer.await() }.isSuccess)
            assertEquals("new bytes", File(filesDir, "orations/replacement/audio.aac").readText())
            assertEquals(row.copy(downloaded = true, audioBytes = 9), db.orations().byId(row.id))
            assertEquals(1, db.joblog().count(row.id, "downloaded"))
            assertEquals(listOf("audio.aac"), File(filesDir, "orations/replacement").list()!!.toList())
            assertEquals(2, transfers.get())
        } finally {
            releasePlacement.countDown()
            releaseRoom.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `stale download snapshot preserves already available bytes without transfer`() = runBlocking {
        val coord = buildCoordinator()
        coord.awaitInitialized()
        assertTrue(coord.connect("http://s", "a@example.com", "pw").isSuccess)
        val snapshot = OrationEntity(
            "available", "Saved", "COMPLETE", "af_heart", 10, null,
            audioBytes = 90, createdAt = 1, updatedAt = 2,
            serverName = "saved.aac", downloaded = false,
        )
        val current = snapshot.copy(downloaded = true, audioBytes = 4)
        db.orations().insert(current)
        val audio = File(filesDir, "orations/available/audio.aac")
        audio.parentFile!!.mkdirs()
        audio.writeText("kept")
        val calls = AtomicInteger()
        server.onDownloadAudio = { _, dest ->
            calls.incrementAndGet()
            dest.parentFile!!.mkdirs()
            dest.writeText("replacement")
        }

        assertTrue(coord.download(snapshot).isSuccess)
        assertEquals("kept", audio.readText())
        assertEquals(current, db.orations().byId("available"))
        assertEquals(0, calls.get())
        assertEquals(0, db.joblog().count("available", "downloaded"))
    }

    @Test
    fun `download stages outside orations across sweepOrphans, tolerates 404 text, and dedupes`() = runBlocking {
        val coord = buildCoordinator()
        coord.awaitInitialized()
        coord.connect("http://s", "a@example.com", "pw")

        val row = OrationEntity(
            id = "o-dl",
            title = "Server Essay",
            status = "COMPLETE",
            voiceId = "af_heart",
            words = 10,
            chunksTotal = null,
            audioBytes = 10,
            createdAt = 1_000L,
            updatedAt = 1_000L,
            serverName = "server-essay.aac",
            serverMtime = 50L,
            downloaded = false,
        )
        db.orations().insert(row)

        val dlStarted = CompletableDeferred<Unit>()
        val releaseDl = CompletableDeferred<Unit>()
        val dlCalls = AtomicInteger(0)
        server.onDownloadAudio = { _, dest ->
            dlCalls.incrementAndGet()
            assertFalse(dest.absolutePath.contains("/orations/"))
            dest.parentFile?.mkdirs()
            dest.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            dlStarted.complete(Unit)
            releaseDl.await()
        }
        server.onDownloadText = { _, _ -> false }

        val first = async { coord.download(row) }
        dlStarted.await()
        val duplicate = async { coord.download(row) }
        // OrationPublication.sweepOrphans must NOT disturb active staging!
        publication.sweepOrphans()
        releaseDl.complete(Unit)

        assertTrue(first.await().isSuccess)
        assertTrue(duplicate.await().isSuccess)
        assertEquals(1, dlCalls.get())
        val updated = db.orations().byId("o-dl")!!
        assertTrue(updated.downloaded)
        assertEquals(5L, updated.audioBytes)
        assertTrue(File(filesDir, "orations/o-dl/audio.aac").exists())
        assertFalse(File(filesDir, "orations/o-dl/source.md").exists())
        assertEquals(1, db.joblog().count("o-dl", "downloaded"))
    }

    @Test
    fun `deletingIds cancels download and zero-row rollback deletes only attempt-owned files`() = runBlocking {
        val coord = buildCoordinator()
        coord.awaitInitialized()
        coord.connect("http://s", "a@example.com", "pw")

        // Case 1: deletingIds becomes active during download
        val deletingRow = OrationEntity(
            id = "o-del",
            title = "Deleted Mid Download",
            status = "COMPLETE",
            voiceId = "af_heart",
            words = 10,
            chunksTotal = null,
            audioBytes = 10,
            createdAt = 1_000L,
            updatedAt = 1_000L,
            serverName = "del.aac",
            serverMtime = 1L,
            downloaded = false,
        )
        db.orations().insert(deletingRow)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        server.onDownloadAudio = { _, dest ->
            dest.parentFile?.mkdirs()
            dest.writeText("temp")
            started.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val job = async { coord.download(deletingRow) }
        started.await()
        narrationFacts.value = NarrationFacts(deletingIds = setOf("o-del"))
        withTimeout(2_000) { cancelled.await() }
        assertTrue(job.await().isFailure)
        assertFalse(File(filesDir, "orations/o-del/audio.aac").exists())
        assertEquals(0, db.joblog().count("o-del", "downloaded"))

        // Case 2: Row deleted from Room right before promotion -> zero-row rollback cleans ONLY attempt-owned files
        narrationFacts.value = NarrationFacts()
        val ghostRow = deletingRow.copy(id = "o-ghost", serverName = "ghost.aac")
        db.orations().insert(ghostRow)
        val existingUnrelated = File(filesDir, "orations/o-ghost/keep.txt").apply {
            parentFile?.mkdirs()
            writeText("keep-me")
        }
        server.onDownloadAudio = { _, dest ->
            dest.parentFile?.mkdirs()
            dest.writeBytes(byteArrayOf(9, 9, 9))
            // Delete DB row right before download finishes so setDownloaded returns 0 rows.
            db.orations().delete("o-ghost")
        }
        server.onDownloadText = { _, dest ->
            dest.parentFile?.mkdirs()
            dest.writeText("ghost text")
            true
        }
        val ghostRes = coord.download(ghostRow)
        assertTrue(ghostRes.isFailure)
        assertFalse(File(filesDir, "orations/o-ghost/audio.aac").exists())
        assertFalse(File(filesDir, "orations/o-ghost/source.md").exists())
        assertTrue(existingUnrelated.exists())
        assertEquals(0, db.joblog().count("o-ghost", "downloaded"))
    }

    @Test
    fun `server narration state machine reconciles on done and fails loudly on bad terminal or poll errors`() = runBlocking {
        val coord = buildCoordinator(pollIntervalMs = 5L, maxPollFailures = 3)
        coord.awaitInitialized()
        coord.connect("http://s", "a@example.com", "pw")

        // 1. Success path: transient failures reset after a good poll, then "done" + name triggers syncNow
        val pollSeq = ArrayDeque(
            listOf<Result<ServerJob>>(
                Result.failure(IOException("flaky 1")),
                Result.failure(IOException("flaky 2")),
                Result.success(ServerJob("running", 40, null, null)),
                Result.failure(IOException("flaky 3")),
                Result.success(ServerJob("done", 100, "nar.aac", null)),
            ),
        )
        server.onJobStatus = { pollSeq.removeFirst().getOrThrow() }
        server.libraryEntries = listOf(RemoteEntry("nar.aac", "Server Story", 120, 30, "onyx", 4096, 30, 888L, true))

        val req1 = coord.narrateOnServer("hello world", "onyx", "Server Story")
        withTimeout(2_000) {
            while (coord.state.value.serverNarration !is ServerNarrationState.Completed) {
                kotlinx.coroutines.delay(5)
            }
        }
        val doneState = coord.state.value.serverNarration as ServerNarrationState.Completed
        assertEquals(req1, doneState.requestId)
        assertEquals("nar.aac", doneState.name)
        assertEquals("Server Story", db.orations().byServerName("nar.aac")?.title)
        coord.acknowledgeServerNarration(req1)
        assertEquals(ServerNarrationState.Idle, coord.state.value.serverNarration)

        // 2. Loud failures across all edge conditions
        val failureCases = listOf<() -> ServerJob>(
            { ServerJob("stopped", 20, null, null) },
            { ServerJob("error", 20, null, null) }, // null error must still fail loudly
            { ServerJob("done", 100, null, null) }, // done without name
            { ServerJob("mystery", 10, null, null) }, // unrecognized status
            { throw ApiException(404, "missing") },
            { throw IOException("down") }, // hits maxPollFailures = 3
        )
        for (caseFn in failureCases) {
            server.onJobStatus = { caseFn() }
            val req = coord.narrateOnServer("body", "onyx", "Case")
            withTimeout(2_000) {
                while (coord.state.value.serverNarration !is ServerNarrationState.Failed) {
                    kotlinx.coroutines.delay(5)
                }
            }
            val failed = coord.state.value.serverNarration as ServerNarrationState.Failed
            assertEquals(req, failed.requestId)
            assertTrue(failed.message.isNotBlank())
            coord.acknowledgeServerNarration(req)
        }

        // 3. 401 expires session and terminates loudly
        server.onJobStatus = { throw UnauthorizedApiException("expired") }
        coord.narrateOnServer("body", "onyx", "Auth Fail")
        withTimeout(2_000) {
            while (coord.state.value.serverNarration !is ServerNarrationState.Failed) {
                kotlinx.coroutines.delay(5)
            }
        }
        assertNull(coord.state.value.accountEmail)
        assertNull(store.load())
    }

    @Test
    fun `narrateOnServer does not return active job requestId for a different request`() = runBlocking {
        val coord = buildCoordinator()
        coord.awaitInitialized()
        assertTrue(coord.connect("https://example.test", "user@example.com", "pw").isSuccess)

        val pollGate = CompletableDeferred<ServerJob>()
        server.onJobStatus = { pollGate.await() }

        val firstReqId = coord.narrateOnServer("first text", "onyx", "First")
        withTimeout(2_000) {
            while (coord.state.value.serverNarration !is ServerNarrationState.Polling) {
                kotlinx.coroutines.delay(5)
            }
        }

        val secondReqId = coord.narrateOnServer("second text", "onyx", "Second")
        pollGate.complete(ServerJob("done", 100, "nar.aac", null))

        org.junit.Assert.assertNotEquals(firstReqId, secondReqId)
        withTimeout(2_000) {
            while (coord.state.value.serverNarration !is ServerNarrationState.Completed) {
                kotlinx.coroutines.delay(5)
            }
        }
        assertEquals(secondReqId, (coord.state.value.serverNarration as ServerNarrationState.Completed).requestId)
    }

    @Test
    fun `stale epoch reconciliation finally must not clear newer epoch syncingLibrary`() = runBlocking {
        val coord = buildCoordinator()
        coord.awaitInitialized()
        assertTrue(coord.connect("https://example.test", "one@example.com", "pw").isSuccess)

        val epoch1Entered = CompletableDeferred<Unit>()
        val releaseEpoch1Unwind = CompletableDeferred<Unit>()
        val epoch2Entered = CompletableDeferred<Unit>()
        val releaseEpoch2 = CompletableDeferred<Unit>()
        server.onLibrary = { token ->
            if (token == "tok-one@example.com") {
                epoch1Entered.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                } finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        releaseEpoch1Unwind.await()
                    }
                }
                emptyList()
            } else {
                epoch2Entered.complete(Unit)
                releaseEpoch2.await()
                emptyList()
            }
        }

        val sync1 = async { coord.syncNow() }
        withTimeout(2_000) { epoch1Entered.await() }

        val connect2 = async { coord.connect("https://example.test", "two@example.com", "pw") }
        withTimeout(2_000) {
            while (coord.state.value.accountEmail != "two@example.com") kotlinx.coroutines.delay(5)
        }

        val sync2 = async { coord.syncNow() }
        withTimeout(2_000) { epoch2Entered.await() }
        assertTrue(coord.state.value.syncingLibrary)

        releaseEpoch1Unwind.complete(Unit)
        withTimeout(2_000) { sync1.await() }
        withTimeout(2_000) { connect2.await() }

        try {
            assertTrue(coord.state.value.syncingLibrary)
        } finally {
            releaseEpoch2.complete(Unit)
            sync2.await()
        }
    }

    @Test
    fun `expired epoch sync cleanup does not clear newer epoch syncingLibrary flag`() = runBlocking {
        val coord = buildCoordinator()
        coord.awaitInitialized()
        assertTrue(coord.connect("https://s1.example.test", "a@example.com", "pw1").isSuccess)
        val sync1Entered = CompletableDeferred<Unit>()
        val releaseSync1 = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val sync2Entered = CompletableDeferred<Unit>()
        val releaseSync2 = CompletableDeferred<Unit>()
        server.onLibrary = { token ->
            if (token == "tok-a@example.com") {
                sync1Entered.complete(Unit)
                try {
                    releaseSync1.await()
                } finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        releaseCleanup.await()
                    }
                }
                emptyList()
            } else {
                sync2Entered.complete(Unit)
                releaseSync2.await()
                emptyList()
            }
        }
        val sync1 = async { coord.syncNow() }
        withTimeout(2_000) { sync1Entered.await() }
        server.onJobStatus = { throw UnauthorizedApiException("expired") }
        coord.narrateOnServer("x", "onyx", "X")
        withTimeout(2_000) { while (coord.state.value.accountEmail != null) kotlinx.coroutines.delay(5) }
        assertTrue(coord.connect("https://s2.example.test", "b@example.com", "pw2").isSuccess)
        val sync2 = async { coord.syncNow() }
        withTimeout(2_000) { sync2Entered.await() }
        assertTrue(coord.state.value.syncingLibrary)
        releaseCleanup.complete(Unit)
        withTimeout(2_000) { sync1.await() }
        try {
            assertTrue("Stale epoch cleared active epoch syncingLibrary", coord.state.value.syncingLibrary)
        } finally {
            releaseSync2.complete(Unit)
            sync2.await()
        }
    }

    @Test
    fun `401 during post-rename refresh does not overwrite expired state with accountSyncStatus`() = runBlocking {
        val coord = buildCoordinator()
        coord.awaitInitialized()
        assertTrue(coord.connect("https://example.test", "user@example.com", "pw").isSuccess)

        val remote = RemoteEntry("remote.aac", "Remote Title", 10, 5, "onyx", 100, 5, 100L, false)
        db.orations().insert(
            OrationEntity(
                id = "o1",
                title = "Local Rename",
                status = "COMPLETE",
                voiceId = "af_heart",
                words = 10,
                chunksTotal = null,
                audioBytes = 100,
                createdAt = 1_000L,
                updatedAt = 200_000L,
                serverName = "remote.aac",
                serverMtime = 100L,
                downloaded = false,
            ),
        )
        val calls = AtomicInteger(0)
        server.onLibrary = {
            if (calls.incrementAndGet() == 1) listOf(remote) else throw UnauthorizedApiException("expired")
        }

        coord.syncNow()

        assertNull(coord.state.value.accountEmail)
        assertNull(coord.state.value.accountSyncStatus)
    }

    @Test
    fun `upload dispatches missing audio check onto ioDispatcher off caller thread`() = runBlocking {
        val ioDispatches = AtomicInteger(0)
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val ioThreadName = "test-io-worker"
        val worker = java.util.concurrent.Executor { cmd ->
            ioDispatches.incrementAndGet()
            exec.execute {
                val prev = Thread.currentThread().name
                Thread.currentThread().name = ioThreadName
                try {
                    cmd.run()
                } finally {
                    Thread.currentThread().name = prev
                }
            }
        }.asCoroutineDispatcher()
        val coord = RealSyncCoordinator(
            db = db,
            filesDir = filesDir,
            api = server,
            credentials = store,
            narrationFacts = narrationFacts,
            publication = publication,
            ioDispatcher = worker,
            clock = { nowMs },
            idGenerator = { "id-${nextId.getAndIncrement()}" },
        ).also { coordinators.add(it) }
        try {
            coord.awaitInitialized()
            assertTrue(coord.connect("http://s", "a@example.com", "pw").isSuccess)
            val before = ioDispatches.get()
            val row = OrationEntity(
                id = "o-missing",
                title = "Missing",
                status = "COMPLETE",
                voiceId = "af_heart",
                words = 5,
                chunksTotal = null,
                audioBytes = 10,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                downloaded = true,
            )
            val res = coord.upload(row)
            assertTrue(res.isFailure)
            assertTrue(res.exceptionOrNull() is IllegalArgumentException)
            assertTrue("Expected upload missing-audio check to dispatch to ioDispatcher", ioDispatches.get() > before)
        } finally {
            coordinators.remove(coord)
            coord.shutdown().await()
            exec.shutdownNow()
        }
    }

    private class InMemoryCredentialStore : CredentialStore {
        private var current: SyncCredentials? = null
        override fun load(): SyncCredentials? = synchronized(this) { current }
        override fun save(credentials: SyncCredentials) = synchronized(this) { current = credentials }
        override fun clear() = synchronized(this) { current = null }
    }

    private class FakeRemoteServer : RemoteServer {
        var libraryEntries: List<RemoteEntry> = emptyList()
        val renames = CopyOnWriteArrayList<Pair<String, String>>()
        var onLibrary: (suspend (String) -> List<RemoteEntry>)? = null
        var onDownloadAudio: (suspend (String, File) -> Unit)? = null
        var onDownloadText: (suspend (String, File) -> Boolean)? = null
        var onUpload: (suspend (String) -> Boolean)? = null
        var onJobStatus: (suspend (String) -> ServerJob)? = null

        override suspend fun requestToken(serverUrl: String, email: String, password: String, label: String): String =
            "tok-$email"

        override suspend fun account(serverUrl: String, token: String): ServerAccount =
            ServerAccount("user@example.com", true, listOf("onyx"), "onyx")

        override suspend fun library(serverUrl: String, token: String): List<RemoteEntry> =
            onLibrary?.invoke(token) ?: libraryEntries

        override suspend fun downloadAudio(
            serverUrl: String,
            token: String,
            name: String,
            dest: File,
            onBytes: ((Long) -> Unit)?,
        ) {
            val hook = onDownloadAudio
            if (hook != null) {
                hook(name, dest)
                onBytes?.invoke(dest.length())
            } else {
                dest.parentFile?.mkdirs()
                dest.writeBytes(byteArrayOf(1, 2, 3))
                onBytes?.invoke(3L)
            }
        }

        override suspend fun downloadText(serverUrl: String, token: String, name: String, dest: File): Boolean =
            onDownloadText?.invoke(name, dest) ?: false

        override suspend fun upload(
            serverUrl: String,
            token: String,
            name: String,
            audio: File,
            text: File?,
            metaJson: String,
            ifNoneMatch: Boolean,
        ): Boolean = onUpload?.invoke(name) ?: true

        override suspend fun rename(serverUrl: String, token: String, name: String, title: String) {
            renames.add(name to title)
        }

        override suspend fun narrate(serverUrl: String, token: String, text: String, voice: String, title: String): String =
            "job-1"

        override suspend fun jobStatus(serverUrl: String, token: String, jobId: String): ServerJob =
            onJobStatus?.invoke(jobId) ?: ServerJob("done", 100, "nar.aac", null)
    }
}
