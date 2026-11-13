package page.stephens.sotto.shell.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import page.stephens.sotto.engine.store.SottoDatabase
import page.stephens.sotto.shell.service.NarrationCoordinatorProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class ConfirmCreationTest {
    @get:Rule val compose = createAndroidComposeRule<SottoActivity>()

    @After
    fun cleanUp() {
        val activity = compose.activity
        runBlocking(Dispatchers.IO) {
            NarrationCoordinatorProvider.shutdownForTest()?.await()
            SottoDatabase.open(activity).clearAllTables()
            File(activity.filesDir, "orations").deleteRecursively()
        }
    }

    @Test
    fun `confirm stays open and prevents another submission until creation commits`() {
        compose.onNodeWithText("+  Write").performClick()
        compose.onNodeWithText("Paste or type what Sotto should read").performTextReplacement("A saved document.")
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Name").performTextReplacement("My oration")
        compose.onNodeWithText("Narrate").performScrollTo()

        val db = SottoDatabase.open(compose.activity)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread {
            db.runInTransaction {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            compose.onNodeWithText("Narrate").performClick()
            compose.onNodeWithText("New oration").assertExists()
            compose.onNodeWithText("Narrate").assertIsNotEnabled()
        } finally {
            release.countDown()
            holder.join(15_000)
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("New oration").fetchSemanticsNodes().isEmpty()
        }
        assertEquals("My oration", runBlocking(Dispatchers.IO) { db.orations().all().single().title })
        compose.onNodeWithText("New oration").assertDoesNotExist()
    }

    @Test
    fun `save failure keeps the name voice and text for a successful retry`() {
        val activity = compose.activity
        val db = SottoDatabase.open(activity)
        val root = File(activity.filesDir, "orations")
        root.writeText("storage unavailable")
        compose.onNodeWithText("+  Write").performClick()
        compose.onNodeWithText("Paste or type what Sotto should read").performTextReplacement("Keep this document.")
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Name").performTextReplacement("Retain this name")
        compose.onAllNodesWithText("choose")[0].performScrollTo().performClick()
        compose.onNodeWithText("Narrate").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Could not save the oration.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("New oration").assertExists()
        compose.onNodeWithText("Narrate").assertIsEnabled()
        assertNull(shadowOf(activity).nextStartedService)
        assertTrue(runBlocking(Dispatchers.IO) { db.orations().all().isEmpty() })
        root.delete()
        compose.onNodeWithText("Narrate").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("New oration").fetchSemanticsNodes().isEmpty()
        }
        val row = runBlocking(Dispatchers.IO) { db.orations().all().single() }
        assertEquals("Retain this name", row.title)
        assertEquals("af_bella", row.voiceId)
        assertEquals("Keep this document.", File(root, "${row.id}/source.md").readText())
        assertEquals(1, runBlocking(Dispatchers.IO) { db.joblog().count(row.id, "created") })
        val command = shadowOf(activity).nextStartedService
        assertEquals(page.stephens.sotto.shell.service.InAppNarrationService.ACTION_NARRATE, command.action)
        assertEquals(row.id, command.getStringExtra(page.stephens.sotto.shell.service.InAppNarrationService.EXTRA_ID))
        assertEquals(1, command.extras!!.size())
        compose.onNodeWithText("New oration").assertDoesNotExist()
    }
}
