package page.stephens.sotto.shell.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import page.stephens.sotto.engine.store.SottoDatabase
import page.stephens.sotto.engine.text.Intake
import page.stephens.sotto.shell.service.NarrationCoordinatorProvider
import page.stephens.sotto.shell.sync.SyncCoordinatorProvider
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class DocumentIntakeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val uri = Uri.parse("content://documents/intake.txt")

    @Before fun resetToasts() { ShadowToast.reset() }

    @After fun cleanUp() = runBlocking(Dispatchers.IO) {
        SyncCoordinatorProvider.shutdownForTest()?.await()
        NarrationCoordinatorProvider.shutdownForTest()?.await()
        SottoDatabase.open(app).clearAllTables()
    }

    private fun intent(action: String) = Intent(app, SottoActivity::class.java).setAction(action).setType("text/plain")
    private fun register(open: () -> InputStream?) {
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { open() }
    }
    private fun waitFor(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun assertNoPublication(scenario: ActivityScenario<SottoActivity>) {
        runBlocking(Dispatchers.IO) {
            val db = SottoDatabase.open(app)
            assertTrue(db.orations().all().isEmpty())
            assertTrue(db.joblog().recent().isEmpty())
        }
        scenario.onActivity { assertNull(shadowOf(it).nextStartedService) }
    }

    @Test fun `shared text takes precedence and complete normalized text appears on Confirm`() {
        register { error("EXTRA_STREAM must not open when EXTRA_TEXT exists") }
        ActivityScenario.launch<SottoActivity>(intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_TEXT, " \n# Shared\n\nFull text. \n").putExtra(Intent.EXTRA_STREAM, uri)).use { scenario ->
            waitFor("Name")
            compose.onNode(hasText("# Shared\n\nFull text.") and hasSetTextAction().not()).performScrollTo().assertExists()
            assertNoPublication(scenario)
        }
    }

    @Test fun `send stream and view accept the complete file after long surrounding whitespace`() {
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_VIEW)) {
            register { ("\uFEFF" + " ".repeat(819_201) + "Complete file." + " ".repeat(900_000)).byteInputStream() }
            val launch = intent(action).apply {
                if (action == Intent.ACTION_SEND) putExtra(Intent.EXTRA_STREAM, uri) else data = uri
            }
            ActivityScenario.launch<SottoActivity>(launch).use { scenario ->
                waitFor("Name")
                compose.onNode(hasText("Complete file.") and hasSetTextAction().not()).performScrollTo().assertExists()
                assertNoPublication(scenario)
            }
        }
    }

    @Test fun `unsupported actions are ignored and missing or empty text keeps its precedence`() {
        ActivityScenario.launch<SottoActivity>(intent("unsupported").putExtra(Intent.EXTRA_STREAM, uri)).use {
            waitFor("+  Write")
            assertNull(ShadowToast.getTextOfLatestToast())
        }
        for (launch in listOf(intent(Intent.ACTION_SEND), intent(Intent.ACTION_VIEW),
            intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "  ").putExtra(Intent.EXTRA_STREAM, uri))) {
            register { error("empty text must not fall through to the stream") }
            ActivityScenario.launch<SottoActivity>(launch).use { scenario ->
                waitFor("+  Write")
                assertEquals("Nothing to narrate - the text was empty", ShadowToast.getTextOfLatestToast())
                assertNoPublication(scenario)
            }
        }
    }

    @Test fun `interior whitespace rejection reports the limit without a partial size`() {
        register { ("A" + " ".repeat(819_200) + "TAIL").byteInputStream() }
        ActivityScenario.launch<SottoActivity>(intent(Intent.ACTION_VIEW).setData(uri)).use { scenario ->
            waitFor("+  Write")
            assertEquals("Too long to narrate - the limit is 400 KB", ShadowToast.getTextOfLatestToast())
            assertNoPublication(scenario)
        }
    }

    @Test fun `open access read and close failures show unreadable without publishing`() {
        val sources: List<() -> InputStream?> = listOf(
            { throw SecurityException("denied") }, { throw IOException("open") },
            { object : ByteArrayInputStream(byteArrayOf(65)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("read")
            } },
            { object : ByteArrayInputStream(byteArrayOf(65)) {
                override fun close() { throw IOException("close") }
            } },
        )
        for (source in sources) {
            register(source)
            ActivityScenario.launch<SottoActivity>(intent(Intent.ACTION_VIEW).setData(uri)).use { scenario ->
                waitFor("+  Write")
                assertEquals("Couldn't read that file", ShadowToast.getTextOfLatestToast())
                assertNoPublication(scenario)
            }
        }
    }

    @Test fun `blocked reads leave Reading responsive and Back fences success and failure`() {
        for (fail in listOf(false, true)) {
            val blocked = BlockedStream(fail)
            register {
                assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
                blocked
            }
            ActivityScenario.launch<SottoActivity>(intent(Intent.ACTION_VIEW).setData(uri)).use { scenario ->
                try {
                    waitFor("Reading document...")
                    assertTrue(blocked.entered.await(5, TimeUnit.SECONDS))
                    scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                    waitFor("+  Write")
                    blocked.release.countDown()
                    assertTrue(blocked.closed.await(5, TimeUnit.SECONDS))
                    compose.waitForIdle()
                    compose.onNodeWithText("Name").assertDoesNotExist()
                    assertNull(ShadowToast.getTextOfLatestToast())
                    assertNoPublication(scenario)
                } finally { blocked.release.countDown() }
            }
        }
    }

    @Test fun `recreation restarts unfinished intake and fences the old Activity result`() {
        for (fail in listOf(false, true)) {
            val old = BlockedStream(fail)
            val opens = AtomicInteger()
            register { if (opens.incrementAndGet() == 1) old else "Replacement document.".byteInputStream() }
            ActivityScenario.launch<SottoActivity>(intent(Intent.ACTION_VIEW).setData(uri)).use { scenario ->
                try {
                    waitFor("Reading document...")
                    assertTrue(old.entered.await(5, TimeUnit.SECONDS))
                    scenario.recreate()
                    waitFor("Name")
                    old.release.countDown()
                    assertTrue(old.closed.await(5, TimeUnit.SECONDS))
                    compose.waitForIdle()
                    compose.onNode(hasText("Replacement document.") and hasSetTextAction().not()).performScrollTo().assertExists()
                    assertEquals(2, opens.get())
                    assertNull(ShadowToast.getTextOfLatestToast())
                    assertNoPublication(scenario)
                } finally { old.release.countDown() }
            }
        }
    }

    @Test fun `restored Confirm keeps text name and voice without reopening and Library does not repeat errors`() {
        val opens = AtomicInteger()
        register {
            check(opens.incrementAndGet() == 1) { "completed intake reopened" }
            "Saved document.".byteInputStream()
        }
        ActivityScenario.launch<SottoActivity>(intent(Intent.ACTION_VIEW).setData(uri)).use { scenario ->
            waitFor("Name")
            compose.onNodeWithText("Name").performTextReplacement("Chosen name")
            compose.onAllNodesWithText("choose")[0].performScrollTo().performClick()
            scenario.recreate()
            waitFor("Name")
            compose.onNodeWithText("Name").assertTextEquals("Name", "Chosen name")
            compose.onNode(hasText("Saved document.") and hasSetTextAction().not()).performScrollTo().assertExists()
            compose.onAllNodesWithText("chosen")[0].performScrollTo().assertExists()
            assertEquals(1, opens.get())
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor("+  Write")
            scenario.recreate()
            waitFor("+  Write")
            assertEquals(1, opens.get())
            assertNoPublication(scenario)
        }
        register { throw IOException("once") }
        ActivityScenario.launch<SottoActivity>(intent(Intent.ACTION_VIEW).setData(uri)).use { scenario ->
            waitFor("+  Write")
            ShadowToast.reset()
            scenario.recreate()
            waitFor("+  Write")
            assertNull(ShadowToast.getTextOfLatestToast())
        }
    }

    @Test fun `Write retains rejected text and accepts a corrected document`() {
        ActivityScenario.launch(SottoActivity::class.java).use { scenario ->
            compose.onNodeWithText("+  Write").performClick()
            val raw = "\uFEFF".repeat(Intake.MAX_INPUT_BYTES / 3 + 1)
            compose.onNodeWithText("Paste or type what Sotto should read").performTextReplacement(raw)
            compose.onNodeWithText("Continue").performSemanticsAction(SemanticsActions.OnClick) { click ->
                click()
                click()
            }
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                ShadowToast.getTextOfLatestToast() != null
            }
            assertEquals(1, ShadowToast.shownToastCount())
            compose.onNodeWithText("Paste or type what Sotto should read")
                .assertTextEquals("Paste or type what Sotto should read", raw)
            compose.onNodeWithText("Paste or type what Sotto should read").performTextReplacement("  Typed document.  ")
            compose.onNodeWithText("Continue").performClick()
            waitFor("Name")
            compose.onNode(hasText("Typed document.") and hasSetTextAction().not()).performScrollTo().assertExists()
            assertNoPublication(scenario)
        }
    }

    private class BlockedStream(private val fail: Boolean) : ByteArrayInputStream("Old document.".toByteArray()) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS))
            if (fail) throw IOException("late failure")
            return super.read(b, off, len)
        }
        override fun close() {
            assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
            closed.countDown()
        }
    }
}
