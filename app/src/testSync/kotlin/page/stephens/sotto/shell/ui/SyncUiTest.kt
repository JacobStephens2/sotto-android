package page.stephens.sotto.shell.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.SottoDatabase
import page.stephens.sotto.shell.service.NarrationCoordinatorProvider
import page.stephens.sotto.shell.sync.ServerAccount
import page.stephens.sotto.shell.sync.ServerNarrationState
import page.stephens.sotto.shell.sync.SyncCoordinator
import page.stephens.sotto.shell.sync.SyncCoordinatorProvider
import page.stephens.sotto.shell.sync.SyncState
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class SyncUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var app: Context
    private lateinit var fake: FakeSyncCoordinator

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        fake = FakeSyncCoordinator()
        SyncCoordinatorProvider.setForTest(fake)
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        runBlocking(Dispatchers.IO) {
            SyncCoordinatorProvider.shutdownForTest()?.await()
            NarrationCoordinatorProvider.shutdownForTest()?.await()
            SottoDatabase.open(app).clearAllTables()
            File(app.filesDir, "orations").deleteRecursively()
        }
    }

    @Test
    fun `account connects, survives activity recreation, syncs across recreate, and disconnects off-main`() {
        ActivityScenario.launch(SottoActivity::class.java).use { scenario ->
            compose.onNodeWithText("Account").performClick()
            compose.waitForIdle()

            compose.onNodeWithText("Email").performTextInput("reader@example.com")
            compose.onNodeWithText("Password").performTextInput("secret")
            compose.onNodeWithText("Connect").performClick()
            compose.waitForIdle()

            compose.onNodeWithText("Connected as reader@example.com").assertIsDisplayed()

            // Recreate Activity while on Account screen; screen and status must persist.
            scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithText("Connected as reader@example.com").assertIsDisplayed()
            compose.onNodeWithText("Connected as reader@example.com", substring = true).assertIsDisplayed()

            // Sync now mid-flight survives recreate() and surfaces completion text on the recreated Activity.
            val syncGate = CompletableDeferred<Result<String>>()
            fake.nextSyncResult = { syncGate.await() }
            compose.onNodeWithText("Sync now").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Syncing...").assertIsNotEnabled()

            scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithText("Syncing...").assertIsNotEnabled()

            syncGate.complete(Result.success("Synced: 2 new from server, 0 renamed here, 0 renamed there"))
            fake.setAccountSyncStatus("Synced: 2 new from server, 0 renamed here, 0 renamed there")
            compose.waitForIdle()
            compose.onNodeWithText("Synced: 2 new from server", substring = true).assertIsDisplayed()

            // Disconnect suspends on coordinator off-main and persists Disconnected. across recreate().
            compose.onNodeWithText("Disconnect").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Connect").assertIsDisplayed()
            compose.onNodeWithText("Disconnected.").assertIsDisplayed()

            scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithText("Connect").assertIsDisplayed()
            compose.onNodeWithText("Disconnected.").assertIsDisplayed()
        }
    }

    @Test
    fun `library shows download progress across recreation, failure toasts, and syncNow feedback`() {
        val row = OrationEntity(
            id = "srv-1",
            title = "Server Book",
            status = "COMPLETE",
            voiceId = "af_heart",
            words = 100,
            chunksTotal = null,
            audioBytes = 4096,
            audioDurationMs = 60_000,
            createdAt = 1_000L,
            updatedAt = 1_000L,
            serverName = "book.aac",
            serverMtime = 10L,
            downloaded = false,
        )
        runBlocking(Dispatchers.IO) { SottoDatabase.open(app).orations().insert(row) }

        ActivityScenario.launch(SottoActivity::class.java).use { scenario ->
            compose.waitUntil(3_000) {
                compose.onAllNodesWithText("Server Book").fetchSemanticsNodes().isNotEmpty()
            }

            // Unauthenticated Library refresh shows clear guidance Toast.
            compose.onNodeWithText("Library").performClick()
            ShadowLooper.idleMainLooper()
            assertEquals("Connect your account first (Account)", ShadowToast.getTextOfLatestToast())

            // Connected Library refresh surfaces summary Toast.
            fake.setConnected("reader@example.com")
            fake.nextSyncResult = { Result.success("Synced: 1 new from server, 0 renamed here, 0 renamed there") }
            compose.onNodeWithText("Library").performClick()
            compose.waitForIdle()
            ShadowLooper.idleMainLooper()
            assertTrue(ShadowToast.getTextOfLatestToast().orEmpty().contains("Synced: 1 new"))

            // Tap server-only item -> coordinator shows download progress even across Activity recreate().
            val dlGate = CompletableDeferred<Result<Unit>>()
            fake.nextDownload = {
                fake.setDownloadPct("srv-1", 45)
                dlGate.await()
            }
            compose.onNodeWithText("Server Book").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Downloading · 45%").assertIsDisplayed()

            scenario.recreate()
            compose.waitUntil(3_000) {
                compose.onAllNodesWithText("Downloading · 45%").fetchSemanticsNodes().isNotEmpty()
            }

            // Duplicate tap during active download is ignored; progress clears when download ends.
            compose.onNodeWithText("Server Book").performClick()
            compose.waitForIdle()
            assertEquals(1, fake.downloadCalls.get())

            dlGate.complete(Result.success(Unit))
            fake.clearDownload("srv-1")
            compose.waitUntil(3_000) {
                compose.onAllNodesWithText("Downloading · 45%").fetchSemanticsNodes().isEmpty()
            }

            // A failing download on the active Activity surfaces a Download failed Toast.
            fake.nextDownload = { Result.failure(IOException("network dropped")) }
            compose.onNodeWithText("Server Book").performClick()
            compose.waitForIdle()
            ShadowLooper.idleMainLooper()
            assertTrue(ShadowToast.getTextOfLatestToast().orEmpty().contains("Download failed: network dropped"))
        }
    }

    @Test
    fun `oration sheet disables duplicate upload taps while uploading and surfaces result toasts`() {
        val localRow = OrationEntity(
            id = "loc-1",
            title = "Local Essay",
            status = "COMPLETE",
            voiceId = "af_heart",
            words = 80,
            chunksTotal = 1,
            chunksDone = 1,
            audioBytes = 2048,
            audioDurationMs = 15_000,
            createdAt = 1_000L,
            updatedAt = 1_000L,
            downloaded = true,
        )
        runBlocking(Dispatchers.IO) { SottoDatabase.open(app).orations().insert(localRow) }
        fake.setConnected("reader@example.com")

        ActivityScenario.launch(SottoActivity::class.java).use {
            compose.waitUntil(3_000) {
                compose.onAllNodesWithText("Local Essay").fetchSemanticsNodes().isNotEmpty()
            }

            val uploadGate = CompletableDeferred<Result<String>>()
            fake.nextUpload = {
                fake.setUploading("loc-1", true)
                uploadGate.await()
            }

            // First upload click starts upload and dismisses sheet.
            compose.onNodeWithText("Local Essay").performTouchInput { longClick() }
            compose.waitForIdle()
            compose.onNodeWithText("Upload to account").performClick()
            compose.waitForIdle()
            assertEquals(1, fake.uploadCalls.get())

            // Opening sheet while still uploading renders Uploading... and disables duplicate clicks.
            compose.onNodeWithText("Local Essay").performTouchInput { longClick() }
            compose.waitForIdle()
            compose.onNodeWithText("Uploading...").performClick()
            compose.waitForIdle()
            assertEquals(1, fake.uploadCalls.get())

            // Finishing upload shows success Toast.
            fake.setUploading("loc-1", false)
            uploadGate.complete(Result.success("Uploaded as local-essay.aac"))
            compose.waitForIdle()
            ShadowLooper.idleMainLooper()
            assertEquals("Uploaded as local-essay.aac", ShadowToast.getTextOfLatestToast())
        }
    }

    @Test
    fun `confirm tracks matched server narration across recreation and ignores unmatched completion`() {
        fake.setConnected("reader@example.com", capabilities = ServerAccount("reader@example.com", true, listOf("onyx"), "onyx"))
        // Seed a stale Completed state from an earlier screen; Confirm must NOT prematurely auto-close!
        fake.setNarrationState(ServerNarrationState.Completed(requestId = "stale-req", name = "old.aac"))

        val sendIntent = Intent(app, SottoActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "A brand new shared passage worth listening to.")
        }

        ActivityScenario.launch<SottoActivity>(sendIntent).use { scenario ->
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Name").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("New oration").assertIsDisplayed()
            compose.onNodeWithText("Narrate on the server (OpenAI)").performClick()
            compose.waitForIdle()

            fake.narrateIdToReturn = "req-1"
            compose.onNodeWithText("Narrate on server").performScrollTo().performClick()
            compose.waitForIdle()

            // Coordinator advances to polling 60%; recreate() preserves Confirm state and progress.
            fake.setNarrationState(ServerNarrationState.Polling("req-1", "job-1", "A brand new shared", 60))
            compose.waitForIdle()
            compose.onNodeWithText("Narrating on server · 60%").assertIsDisplayed()

            scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithText("Narrating on server · 60%").assertIsDisplayed()

            // Matched completion acknowledges request and closes Confirm back to Library.
            fake.setNarrationState(ServerNarrationState.Completed("req-1", "brand-new.aac"))
            compose.waitForIdle()
            assertTrue(fake.acknowledgedRequestIds.contains("req-1"))
            compose.onNodeWithText("+  Write").assertIsDisplayed()
        }
    }

    @Test
    fun `sync state collection stops when activity is stopped`() {
        ActivityScenario.launch(SottoActivity::class.java).use { scenario ->
            compose.waitForIdle()
            assertEquals(1, fake.subscriptionCount())
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            assertEquals(0, fake.subscriptionCount())
        }
    }

    @Test
    fun `server narration switch row meets 48dp touch target`() {
        fake.setConnected("reader@example.com", ServerAccount("reader@example.com", true, listOf("onyx"), "onyx"))
        ActivityScenario.launch<SottoActivity>(
            Intent(app, SottoActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Hello world.")
            },
        ).use {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Name").fetchSemanticsNodes().isNotEmpty() }
            val bounds = compose.onNodeWithText("Narrate on the server (OpenAI)").fetchSemanticsNode().boundsInRoot
            val heightDp = with(compose.density) { bounds.height.toDp() }
            assertTrue("Switch row height $heightDp < 48.dp", heightDp >= 48.dp)
        }
    }

    private class FakeSyncCoordinator : SyncCoordinator {
        private val _state = MutableStateFlow(SyncState(initialized = true))
        override val state: StateFlow<SyncState> = _state.asStateFlow()
        fun subscriptionCount(): Int = _state.subscriptionCount.value

        var nextSyncResult: suspend () -> Result<String> = { Result.success("Synced: 0 new from server, 0 renamed here, 0 renamed there") }
        var nextDownload: suspend (OrationEntity) -> Result<Unit> = { Result.success(Unit) }
        var nextUpload: suspend (OrationEntity) -> Result<String> = { Result.success("Uploaded") }
        var narrateIdToReturn: String = "req-default"
        val downloadCalls = AtomicInteger(0)
        val uploadCalls = AtomicInteger(0)
        val acknowledgedRequestIds = mutableListOf<String>()

        fun setConnected(email: String, capabilities: ServerAccount? = null) {
            _state.update {
                it.copy(
                    accountEmail = email,
                    serverUrl = "https://example.test",
                    capabilities = capabilities,
                    authStatus = null,
                )
            }
        }

        fun setAccountSyncStatus(msg: String?) {
            _state.update { it.copy(syncingLibrary = false, accountSyncStatus = msg) }
        }

        fun setDownloadPct(id: String, pct: Int) {
            _state.update { it.copy(activeDownloads = it.activeDownloads + (id to pct)) }
        }

        fun clearDownload(id: String) {
            _state.update { it.copy(activeDownloads = it.activeDownloads - id) }
        }

        fun setUploading(id: String, uploading: Boolean) {
            _state.update {
                val next = if (uploading) it.activeUploads + id else it.activeUploads - id
                it.copy(activeUploads = next)
            }
        }

        fun setNarrationState(narration: ServerNarrationState) {
            _state.update { it.copy(serverNarration = narration) }
        }

        override suspend fun awaitInitialized() = Unit

        override suspend fun connect(serverUrl: String, email: String, password: String): Result<String> {
            val clean = email.trim()
            setConnected(clean)
            return Result.success("Connected as $clean")
        }

        override suspend fun disconnect(): String {
            _state.update {
                it.copy(
                    accountEmail = null,
                    serverUrl = null,
                    capabilities = null,
                    authBusy = false,
                    authStatus = "Disconnected.",
                    accountSyncStatus = null,
                    serverNarration = ServerNarrationState.Idle,
                )
            }
            return "Disconnected."
        }

        override suspend fun syncNow(): Result<String> {
            _state.update { it.copy(syncingLibrary = true) }
            val res = nextSyncResult()
            _state.update {
                it.copy(
                    syncingLibrary = false,
                    accountSyncStatus = res.getOrNull() ?: "Sync failed: ${res.exceptionOrNull()?.message}",
                )
            }
            return res
        }

        override suspend fun download(oration: OrationEntity): Result<Unit> {
            downloadCalls.incrementAndGet()
            return nextDownload(oration)
        }

        override suspend fun upload(oration: OrationEntity): Result<String> {
            uploadCalls.incrementAndGet()
            return nextUpload(oration)
        }

        override suspend fun refreshAccount(force: Boolean): Result<ServerAccount> =
            _state.value.capabilities?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("no capabilities"))

        override fun narrateOnServer(text: String, voice: String, title: String): String {
            val id = narrateIdToReturn
            _state.update { it.copy(serverNarration = ServerNarrationState.Submitting(id, title)) }
            return id
        }

        override fun acknowledgeServerNarration(requestId: String) {
            acknowledgedRequestIds.add(requestId)
            _state.update { it.copy(serverNarration = ServerNarrationState.Idle) }
        }

        override fun shutdown(): Deferred<Unit> = CompletableDeferred(Unit)
    }
}
