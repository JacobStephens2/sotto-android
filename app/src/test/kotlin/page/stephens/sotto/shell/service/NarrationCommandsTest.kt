package page.stephens.sotto.shell.service

// The exported MediaSession service must ignore in-app commands. Any other app
// can start an exported service; delete, narrate, play, stop, and the sleep
// timer are accepted only by the non-exported entry point the UI starts.

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.SottoDatabase
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class NarrationCommandsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = SottoDatabase.open(context)
    private var narration: ServiceController<NarrationService>? = null
    private var inApp: ServiceController<InAppNarrationService>? = null

    @After
    fun tearDown() {
        inApp?.destroy()
        narration?.destroy()
        NarrationService.clearInAppForTest()
    }

    @Test
    fun `the exported narration service ignores delete, narrate, play, stop, and the sleep timer`() {
        val id = "exported-ignores"
        insert(id, status = "COMPLETE", audioBytes = 4, withAudio = true)
        val service = startNarration()
        val player = sessionPlayer(service)
        val itemsBefore = player.mediaItemCount

        listOf(
            Intent(InAppNarrationService.ACTION_DELETE).putExtra(InAppNarrationService.EXTRA_ID, id),
            Intent(InAppNarrationService.ACTION_NARRATE).putExtra(InAppNarrationService.EXTRA_ID, id),
            Intent(InAppNarrationService.ACTION_PLAY).putExtra(InAppNarrationService.EXTRA_ID, id),
            Intent(InAppNarrationService.ACTION_STOP_NARRATION),
            Intent(InAppNarrationService.ACTION_SLEEP_TIMER).putExtra(InAppNarrationService.EXTRA_MINUTES, 15),
        ).forEach { intent ->
            service.onStartCommand(intent.setComponent(ComponentName(context, NarrationService::class.java)), 0, 1)
        }
        // These commands run on the narration worker, not the main looper.
        // Wait long enough for a handled delete, narrate, or play to show.
        val acted = becameTrue(1_500) {
            status(id) != "COMPLETE" ||
                !File(context.filesDir, "orations/$id").exists() ||
                player.mediaItemCount != itemsBefore ||
                player.playWhenReady ||
                events(id).isNotEmpty()
        }

        assertFalse(acted)
        assertEquals("COMPLETE", status(id))
        assertTrue(File(context.filesDir, "orations/$id").exists())
        assertEquals(itemsBefore, player.mediaItemCount)
        assertFalse(player.playWhenReady)
        assertTrue(events(id).isEmpty())
    }

    @Test
    fun `in-app commands are not exported and the media session service still is`() {
        val inAppInfo = serviceInfo(InAppNarrationService::class.java)
        assertFalse(isExported(inAppInfo))
        val narrationInfo = serviceInfo(NarrationService::class.java)
        assertTrue(isExported(narrationInfo))

        val media = query(Intent(MediaSessionService.SERVICE_INTERFACE).setPackage(context.packageName))
        assertTrue(media.any { it.serviceInfo.name == NarrationService::class.java.name })
        val delete = query(Intent(InAppNarrationService.ACTION_DELETE).setPackage(context.packageName))
        assertTrue(delete.isEmpty())

        val service = startNarration()
        assertNotNull(sessionPlayer(service))
    }

    @Test
    fun `the in-app entry point deletes an oration the exported service would not`() {
        val id = "in-app-delete"
        insert(id, status = "COMPLETE", audioBytes = 4, withAudio = true, withSource = true)
        startNarration()
        val commands = startInApp()
        commands.onStartCommand(command(InAppNarrationService.ACTION_DELETE, id), 0, 1)
        val dir = File(context.filesDir, "orations/$id")
        await { oration(id) == null && !dir.exists() }

        assertNull(oration(id))
        assertFalse(dir.exists())
        assertTrue(events(id).any { it.event == "deleted" })
    }

    @Test
    fun `a command sent before the narration service exists is applied when that service starts`() {
        val id = "queued-delete"
        insert(id, status = "COMPLETE", withAudio = true)
        val commands = startInApp()
        commands.onStartCommand(command(InAppNarrationService.ACTION_DELETE, id), 0, 1)
        val wake = shadowOf(context as android.app.Application).nextStartedService
        assertNotNull(wake)
        assertEquals(NarrationService::class.java.name, wake.component?.className)
        assertNull(wake.action)

        startNarration(wake)
        val dir = File(context.filesDir, "orations/$id")
        await { oration(id) == null && !dir.exists() }
        assertNull(oration(id))
        assertFalse(dir.exists())
    }

    @Test
    fun `the in-app entry point plays an oration and the exported service does not`() {
        val id = "in-app-play"
        insert(id, status = "COMPLETE", audioBytes = 8, withAudio = true)
        val service = startNarration()
        val player = sessionPlayer(service)

        service.onStartCommand(command(InAppNarrationService.ACTION_PLAY, id), 0, 1)
        idle()
        assertEquals(0, player.mediaItemCount)

        startInApp().onStartCommand(command(InAppNarrationService.ACTION_PLAY, id), 0, 1)
        await { player.mediaItemCount == 1 }
        assertEquals(id, player.currentMediaItem?.mediaId)
        assertTrue(player.playWhenReady)
    }

    @Test
    fun `the in-app sleep timer pauses playback and the exported service does not arm one`() {
        val id = "in-app-sleep"
        insert(id, status = "COMPLETE", audioBytes = 8, withAudio = true)
        val service = startNarration()
        val player = sessionPlayer(service)
        startInApp().onStartCommand(command(InAppNarrationService.ACTION_PLAY, id), 0, 1)
        await { player.playWhenReady }

        val beforeExport = latestLogId()
        service.onStartCommand(
            command(InAppNarrationService.ACTION_SLEEP_TIMER, minutes = 15),
            0, 1,
        )
        shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.MINUTES)
        assertTrue(player.playWhenReady)
        assertFalse(sleepLogged(15, beforeExport))

        val beforeInApp = latestLogId()
        startInApp().onStartCommand(
            command(InAppNarrationService.ACTION_SLEEP_TIMER, minutes = 15),
            0, 1,
        )
        shadowOf(Looper.getMainLooper()).idleFor(14, TimeUnit.MINUTES)
        assertTrue(player.playWhenReady)
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.MINUTES)
        await { !player.playWhenReady }
        assertFalse(player.playWhenReady)
        await { sleepLogged(15, beforeInApp) }
    }

    @Test
    fun `cancelling the sleep timer does not record a pause`() {
        val id = "sleep-cancel"
        insert(id, status = "COMPLETE", audioBytes = 8, withAudio = true)
        val player = sessionPlayer(startNarration())
        val commands = startInApp()
        commands.onStartCommand(command(InAppNarrationService.ACTION_PLAY, id), 0, 1)
        await { player.playWhenReady }
        val before = latestLogId()
        commands.onStartCommand(command(InAppNarrationService.ACTION_SLEEP_TIMER, minutes = 15), 0, 1)
        commands.onStartCommand(command(InAppNarrationService.ACTION_SLEEP_TIMER, minutes = 0), 0, 1)
        shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.MINUTES)
        // The pause and this log are one step. playWhenReady is not stable across
        // a long looper jump, so the log is the signal that the timer did not fire.
        assertFalse(sleepLogged(15, before))
    }

    @Test
    fun `stop narration from the in-app entry point wins before synthesis starts`() {
        val id = "stop-before-start"
        insert(id, status = "STOPPED", withSource = false)
        startNarration()
        val commands = startInApp()
        commands.onStartCommand(command(InAppNarrationService.ACTION_NARRATE, id), 0, 1)
        commands.onStartCommand(command(InAppNarrationService.ACTION_STOP_NARRATION), 0, 1)
        idle()
        await { events(id).any { it.event == "stopped" } }

        assertEquals("STOPPED", status(id))
        assertTrue(events(id).any { it.event == "stopped" })
        assertFalse(events(id).any { it.event == "error" || it.event == "started" })
    }

    @Test
    fun `narrate and stop sent to the exported service leave the oration alone`() {
        val id = "exported-narrate"
        insert(id, status = "STOPPED", withSource = false)
        val service = startNarration()
        service.onStartCommand(command(InAppNarrationService.ACTION_NARRATE, id), 0, 1)
        service.onStartCommand(command(InAppNarrationService.ACTION_STOP_NARRATION), 0, 1)
        idle()
        Thread.sleep(200)

        assertEquals("STOPPED", status(id))
        assertTrue(events(id).isEmpty())
    }

    private fun startNarration(intent: Intent? = null): NarrationService {
        val controller = if (intent == null) {
            Robolectric.buildService(NarrationService::class.java)
        } else {
            Robolectric.buildService(NarrationService::class.java, intent)
        }
        narration = controller.create()
        return controller.get()
    }

    private fun startInApp(): InAppNarrationService {
        val controller = Robolectric.buildService(InAppNarrationService::class.java).create()
        inApp = controller
        return controller.get()
    }

    private fun command(action: String, id: String? = null, minutes: Int? = null): Intent =
        Intent(action).setComponent(ComponentName(context, InAppNarrationService::class.java)).apply {
            id?.let { putExtra(InAppNarrationService.EXTRA_ID, it) }
            minutes?.let { putExtra(InAppNarrationService.EXTRA_MINUTES, it) }
        }

    private fun insert(
        id: String,
        status: String,
        audioBytes: Long = 0,
        withAudio: Boolean = false,
        withSource: Boolean = false,
    ) = runBlocking(Dispatchers.IO) {
        val now = 1_700_000_000_000L
        db.orations().insert(OrationEntity(
            id = id,
            title = "Essay",
            status = status,
            voiceId = "af_heart",
            words = 3,
            chunksTotal = 1,
            chunksDone = 0,
            audioBytes = audioBytes,
            createdAt = now,
            updatedAt = now,
        ))
        val dir = File(context.filesDir, "orations/$id")
        dir.mkdirs()
        if (withSource) File(dir, "source.md").writeText("Hello there friend.")
        if (withAudio) File(dir, "audio.aac").writeBytes(ByteArray(audioBytes.toInt()) { 1 })
    }

    private fun oration(id: String) = runBlocking(Dispatchers.IO) { db.orations().byId(id) }

    private fun status(id: String) = oration(id)?.status

    private fun events(id: String?) = runBlocking(Dispatchers.IO) {
        db.joblog().recent(50).filter { it.orationId == id }
    }

    private fun latestLogId(): Long = runBlocking(Dispatchers.IO) {
        db.joblog().recent(1).firstOrNull()?.id ?: 0L
    }

    private fun sleepLogged(minutes: Int, afterId: Long) = runBlocking(Dispatchers.IO) {
        db.joblog().recent(50).any {
            it.id > afterId && it.event == "sleep-timer" && it.title.contains("$minutes")
        }
    }

    private fun sessionPlayer(service: NarrationService): androidx.media3.common.Player {
        val info = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            context.packageName, 1, 1, 1, 1, true, Bundle.EMPTY,
        )
        val session = service.onGetSession(info)
        assertNotNull(session)
        return session!!.player
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun await(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        becameTrue(timeoutMs, cond)
    }

    private fun becameTrue(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val start = System.nanoTime()
        while (!cond()) {
            idle()
            if (System.nanoTime() - start > timeoutMs * 1_000_000) return false
            Thread.sleep(20)
        }
        return true
    }

    private fun serviceInfo(type: Class<*>): ServiceInfo {
        val component = ComponentName(context, type)
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getServiceInfo(component, 0)
        }
    }

    private fun isExported(info: ServiceInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.exported else false

    private fun query(intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentServices(intent, 0)
        }
}
