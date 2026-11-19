package page.stephens.sotto.shell.ui

import android.os.Looper
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.SottoDatabase
import page.stephens.sotto.shell.playback.OrationPlayer
import page.stephens.sotto.shell.playback.OrationPlayerState
import page.stephens.sotto.shell.playback.PlaybackPersistence
import page.stephens.sotto.shell.playback.PlaybackTimeline
import page.stephens.sotto.shell.service.NarrationCoordinatorProvider
import page.stephens.sotto.shell.sync.SyncCoordinatorProvider

@RunWith(RobolectricTestRunner::class)
class PlayerScreenTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @After
    fun tearDown() {
        OrationPlayer.setFactoryForTest(null)
        PlaybackPersistence.shutdownSharedForTest()
        NarrationCoordinatorProvider.shutdownForTest()
        runBlocking { SyncCoordinatorProvider.shutdownForTest()?.await() }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        runBlocking(Dispatchers.IO) {
            SottoDatabase.open(ctx).clearAllTables()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `DropCapText skips redundant updates and applies bodyColor and bodySizeSp changes`() {
        var text by mutableStateOf("Once upon a time in Alexandria.")
        var capColor by mutableStateOf(Color.Red)
        var bodyColor by mutableStateOf(Color.Black)
        var bodySizeSp by mutableFloatStateOf(15f)
        var tick by mutableIntStateOf(0)

        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.runOnUiThread {
                activity.setContent {
                    DropCapText(
                        text = text,
                        capColor = capColor,
                        bodyColor = bodyColor,
                        bodySizeSp = bodySizeSp,
                        modifier = Modifier.semantics { contentDescription = "tick-$tick" },
                    )
                }
            }
            composeRule.waitForIdle()

            val tv = findTextView(activity.window.decorView) { true }
                ?: error("Expected TextView inside DropCapText")
            val initialTextRef = tv.text
            val initialTextSizePx = tv.textSize
            assertTrue(initialTextRef is Spanned)

            var textChangedCalls = 0
            tv.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    textChangedCalls++
                }

                override fun afterTextChanged(s: Editable?) {}
            })

            // Trigger multiple AndroidView.update passes with identical DropCap inputs.
            for (i in 1..3) {
                activity.runOnUiThread { tick = i }
                composeRule.waitForIdle()
            }
            assertSame(initialTextRef, tv.text)
            assertEquals(0, textChangedCalls)

            // Updating bodyColor applies to TextView without recreating the Spanned text.
            activity.runOnUiThread { bodyColor = Color.Blue }
            composeRule.waitForIdle()
            assertEquals(Color.Blue.toArgb(), tv.currentTextColor)
            assertSame(initialTextRef, tv.text)
            assertEquals(0, textChangedCalls)

            // Updating bodySizeSp updates paint size and re-spans drop cap geometry.
            activity.runOnUiThread { bodySizeSp = 22f }
            composeRule.waitForIdle()
            assertTrue(tv.textSize > initialTextSizePx)
            val resizedTextRef = tv.text
            assertNotSame(initialTextRef, resizedTextRef)
            assertEquals(1, textChangedCalls)

            // Updating text re-spans once.
            activity.runOnUiThread { text = "Second paragraph starts here." }
            composeRule.waitForIdle()
            assertEquals(2, textChangedCalls)
            assertTrue(tv.text.toString().startsWith("econd"))
        } finally {
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `PlayerScreen isolates timeline ticks delegates controls and holds slider drag until release`() {
        val readyPreload = OrationPlayerState.Ready(
            id = "doc-1",
            title = "Scriptorium Notes",
            status = "COMPLETE",
            words = 400,
            chunksDone = 4,
            chunksTotal = 4,
            errorMessage = null,
            downloaded = true,
            audioBytes = 192_000L,
            isLoaded = false,
            isPlaying = false,
            catchingUp = false,
            speed = 1.0f,
            sleepMinutes = null,
            sourceText = "Once upon a time in the scriptorium.",
        )
        val fake = FakeOrationPlayer(
            initialState = readyPreload,
            initialTimeline = PlaybackTimeline(positionMs = 75_000L, durationMs = 240_000L),
        )
        OrationPlayer.setFactoryForTest { requestedId ->
            assertEquals("doc-1", requestedId)
            fake
        }
        seedOration(
            OrationEntity(
                id = "doc-1",
                title = "Scriptorium Notes",
                status = "COMPLETE",
                voiceId = "af_heart",
                words = 400,
                chunksTotal = 4,
                chunksDone = 4,
                audioBytes = 192_000L,
                audioDurationMs = 240_000L,
                createdAt = 100L,
                updatedAt = 100L,
                playheadMs = 75_000L,
                downloaded = true,
            ),
        )

        ActivityScenario.launch(SottoActivity::class.java).use { scenario ->
            waitForText("Scriptorium Notes")
            composeRule.onNodeWithText("Scriptorium Notes").performClick()
            waitForText("1:15")

            composeRule.onNodeWithText("4:00").assertIsDisplayed()
            composeRule.onNodeWithText("400 words").assertIsDisplayed()
            composeRule.onNodeWithText("Play").assertIsDisplayed()
            composeRule.onNodeWithText("1.0x").assertIsSelected()

            // SetProgress when !isLoaded must not dispatch seekTo.
            val slider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
            composeRule.onNode(slider).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
            composeRule.waitForIdle()
            assertTrue(fake.seekTos.isEmpty())

            // Capture DropCapText TextView and confirm 5 timeline ticks leave it untouched.
            var dropCapTv: TextView? = null
            scenario.onActivity { activity ->
                dropCapTv = findTextView(activity.window.decorView) {
                    it.text.toString().contains("nce upon a time")
                }
            }
            val tv = requireNotNull(dropCapTv) { "Expected DropCapText TextView in PlayerScreen" }
            val initialTextRef = tv.text
            var textChangedCalls = 0
            tv.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    textChangedCalls++
                }

                override fun afterTextChanged(s: Editable?) {}
            })

            listOf(80_000L, 90_000L, 100_000L, 110_000L, 120_000L).forEach { pos ->
                fake.emitTimeline(PlaybackTimeline(positionMs = pos, durationMs = 240_000L))
                composeRule.waitForIdle()
            }
            composeRule.onNodeWithText("2:00").assertIsDisplayed()
            assertSame(initialTextRef, tv.text)
            assertEquals(0, textChangedCalls)

            // Transition to loaded + playing + active speed/sleep chips.
            fake.emitState(
                readyPreload.copy(
                    isLoaded = true,
                    isPlaying = true,
                    speed = 1.25f,
                    sleepMinutes = 30,
                ),
            )
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Pause").assertIsDisplayed()
            composeRule.onNodeWithText("1.25x").assertIsSelected()
            composeRule.onNodeWithText("30 min").assertIsSelected()
            composeRule.onNodeWithText("15 min").assertIsNotSelected()

            // Loaded SetProgress commits exactly one seekTo.
            composeRule.onNode(slider).performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
            composeRule.waitForIdle()
            assertEquals(listOf(180_000L), fake.seekTos)

            // Touch drag keeps drag local across timeline ticks and commits once on release.
            composeRule.onNode(slider).performTouchInput {
                down(Offset(left + 10f, centerY))
                moveTo(Offset(centerX + 40f, centerY))
            }
            composeRule.waitForIdle()
            fake.emitTimeline(PlaybackTimeline(positionMs = 15_000L, durationMs = 240_000L))
            composeRule.waitForIdle()
            assertEquals(1, fake.seekTos.size)
            composeRule.onNode(slider).performTouchInput { up() }
            composeRule.waitForIdle()
            assertEquals(2, fake.seekTos.size)

            // Controls delegate to OrationPlayer.
            composeRule.onNodeWithText("Pause").performClick()
            composeRule.onNodeWithText("« 15s").performClick()
            composeRule.onNodeWithText("15s »").performClick()
            composeRule.onNodeWithText("1.5x").performClick()
            composeRule.onNodeWithText("30 min").performClick()
            composeRule.onNodeWithText("15 min").performClick()
            composeRule.waitForIdle()
            assertEquals(1, fake.togglePlayPauseCalls)
            assertEquals(1, fake.skipBackCalls)
            assertEquals(1, fake.skipForwardCalls)
            assertEquals(listOf(1.5f), fake.setSpeeds)
            assertEquals(listOf(30, 15), fake.toggleSleepTimers)

            // Catching up + Stop narrating button while NARRATING.
            fake.emitState(
                readyPreload.copy(
                    status = "NARRATING",
                    chunksDone = 2,
                    chunksTotal = 4,
                    isLoaded = true,
                    isPlaying = false,
                    catchingUp = true,
                ),
            )
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Catching up - the narrator is just behind you...").assertIsDisplayed()
            composeRule.onNodeWithText("Stop narrating (keeps what's done)")
                .performScrollTo()
                .performClick()
            composeRule.waitForIdle()
            assertEquals(1, fake.stopNarrationCalls)

            // Navigating back disposes PlayerScreen and closes player.
            composeRule.onNodeWithText("Back").performClick()
            composeRule.waitForIdle()
            assertEquals(1, fake.closeCalls)
        }
    }

    @Test
    fun `PlayerScreen shows Missing view when Oration disappears and closes on Back`() {
        val ready = OrationPlayerState.Ready(
            id = "missing-doc",
            title = "Ephemeral Note",
            status = "COMPLETE",
            words = 50,
            chunksDone = 1,
            chunksTotal = 1,
            errorMessage = null,
            downloaded = true,
            audioBytes = 32_000L,
            isLoaded = false,
            isPlaying = false,
            catchingUp = false,
            speed = 1.0f,
            sleepMinutes = null,
            sourceText = "",
        )
        val fake = FakeOrationPlayer(
            initialState = ready,
            initialTimeline = PlaybackTimeline(positionMs = 0L, durationMs = 4_000L),
        )
        OrationPlayer.setFactoryForTest { fake }
        seedOration(
            OrationEntity(
                id = "missing-doc",
                title = "Ephemeral Note",
                status = "COMPLETE",
                voiceId = "af_heart",
                words = 50,
                chunksTotal = 1,
                chunksDone = 1,
                audioBytes = 32_000L,
                audioDurationMs = 4_000L,
                createdAt = 100L,
                updatedAt = 100L,
                downloaded = true,
            ),
        )

        ActivityScenario.launch(SottoActivity::class.java).use {
            waitForText("Ephemeral Note")
            composeRule.onNodeWithText("Ephemeral Note").performClick()
            waitForText("50 words")

            fake.emitState(OrationPlayerState.Missing)
            fake.emitTimeline(PlaybackTimeline())
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Oration no longer exists").assertIsDisplayed()
            composeRule.onNodeWithText("Back").performClick()
            composeRule.waitForIdle()
            assertEquals(1, fake.closeCalls)
        }
    }

    private fun seedOration(entity: OrationEntity) {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        runBlocking(Dispatchers.IO) {
            SottoDatabase.open(ctx).orations().insert(entity)
        }
    }

    private fun waitForText(text: String) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for '$text'" }
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            Thread.sleep(10)
        }
    }

    private fun findTextView(root: View, predicate: (TextView) -> Boolean): TextView? {
        if (root is TextView && predicate(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findTextView(root.getChildAt(i), predicate)?.let { return it }
            }
        }
        return null
    }

    private class FakeOrationPlayer(
        initialState: OrationPlayerState,
        initialTimeline: PlaybackTimeline,
    ) : OrationPlayer {
        private val _state = MutableStateFlow(initialState)
        override val state: StateFlow<OrationPlayerState> = _state.asStateFlow()

        private val _timeline = MutableStateFlow(initialTimeline)
        override val timeline: StateFlow<PlaybackTimeline> = _timeline.asStateFlow()

        var togglePlayPauseCalls = 0
        val seekTos = mutableListOf<Long>()
        var skipBackCalls = 0
        var skipForwardCalls = 0
        val setSpeeds = mutableListOf<Float>()
        val toggleSleepTimers = mutableListOf<Int>()
        var stopNarrationCalls = 0
        var closeCalls = 0

        fun emitState(next: OrationPlayerState) {
            _state.value = next
        }

        fun emitTimeline(next: PlaybackTimeline) {
            _timeline.value = next
        }

        override fun togglePlayPause() { togglePlayPauseCalls++ }
        override fun seekTo(positionMs: Long) { seekTos += positionMs }
        override fun skipBack() { skipBackCalls++ }
        override fun skipForward() { skipForwardCalls++ }
        override fun setSpeed(speed: Float) { setSpeeds += speed }
        override fun toggleSleepTimer(minutes: Int) { toggleSleepTimers += minutes }
        override fun stopNarration() { stopNarrationCalls++ }
        override fun close() { closeCalls++ }
    }
}
