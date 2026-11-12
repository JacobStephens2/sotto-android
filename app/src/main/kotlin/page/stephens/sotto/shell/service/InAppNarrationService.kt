package page.stephens.sotto.shell.service

// In-app narration commands. Not exported: only this app can start it, and the
// UI is the caller. NarrationService stays exported so the system can bind the
// Media3 session, and it does not accept these actions (ADR-008).

import android.app.Service
import android.content.Intent
import android.os.IBinder

class InAppNarrationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        commandOf(intent)?.let { command ->
            NarrationService.acceptInApp(command)
            // Wake the owner with a blank intent. The command stays in this
            // process; it is not copied onto the exported service.
            startService(Intent(this, NarrationService::class.java))
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_NARRATE = "page.stephens.sotto.NARRATE"
        const val ACTION_PLAY = "page.stephens.sotto.PLAY"
        const val ACTION_STOP_NARRATION = "page.stephens.sotto.STOP_NARRATION"
        const val ACTION_SLEEP_TIMER = "page.stephens.sotto.SLEEP_TIMER"
        const val ACTION_DELETE = "page.stephens.sotto.DELETE"
        const val EXTRA_ID = "orationId"
        const val EXTRA_MINUTES = "minutes"

        internal fun commandOf(intent: Intent?): InAppCommand? {
            val action = intent?.action ?: return null
            val id = intent.getStringExtra(EXTRA_ID)
            return when (action) {
                ACTION_NARRATE -> id?.let(InAppCommand::Narrate)
                ACTION_PLAY -> id?.let(InAppCommand::Play)
                ACTION_STOP_NARRATION -> InAppCommand.StopNarration
                ACTION_SLEEP_TIMER -> InAppCommand.SleepTimer(intent.getIntExtra(EXTRA_MINUTES, 0))
                ACTION_DELETE -> id?.let(InAppCommand::Delete)
                else -> null
            }
        }
    }
}

internal sealed interface InAppCommand {
    data class Narrate(val id: String) : InAppCommand
    data class Play(val id: String) : InAppCommand
    data object StopNarration : InAppCommand
    data class SleepTimer(val minutes: Int) : InAppCommand
    data class Delete(val id: String) : InAppCommand
}
