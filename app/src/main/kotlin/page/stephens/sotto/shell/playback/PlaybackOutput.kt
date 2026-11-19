package page.stephens.sotto.shell.playback

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import java.io.File

internal const val EXTRA_SLEEP_MINUTES = "page.stephens.sotto.SLEEP_MINUTES"

internal data class PlaybackSnapshot(
    val id: String?,
    val positionMs: Long,
    val ended: Boolean,
    val playWhenReady: Boolean,
    val speed: Float,
)

internal data class SavedAudio(val id: String, val title: String, val file: File, val durableBytes: Long)

internal interface PlaybackOutput {
    fun snapshot(): PlaybackSnapshot
    fun replace(audio: SavedAudio, positionMs: Long, speed: Float, playWhenReady: Boolean)
    fun pause()
    fun stopAndClear()
    fun publishSleepTimer(minutes: Int?)
}

internal class Media3PlaybackOutput(
    private val player: Player,
    private val session: MediaSession,
) : PlaybackOutput {
    override fun snapshot() = PlaybackSnapshot(
        player.currentMediaItem?.mediaId, player.currentPosition,
        player.playbackState == Player.STATE_ENDED, player.playWhenReady, player.playbackParameters.speed,
    )

    override fun replace(audio: SavedAudio, positionMs: Long, speed: Float, playWhenReady: Boolean) {
        player.setMediaItem(MediaItem.Builder()
            .setUri(Uri.fromFile(audio.file))
            .setMediaId(audio.id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(audio.title).setArtist("Sotto").build())
            .build(), positionMs)
        player.setPlaybackSpeed(speed)
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    override fun pause() { player.pause() }

    override fun stopAndClear() {
        player.pause()
        player.stop()
        player.clearMediaItems()
    }

    @OptIn(UnstableApi::class)   // MediaSession.sessionExtras is Media3 "unstable" API
    override fun publishSleepTimer(minutes: Int?) {
        val extras = Bundle(session.sessionExtras)
        if (minutes != null && minutes > 0) extras.putInt(EXTRA_SLEEP_MINUTES, minutes)
        else extras.remove(EXTRA_SLEEP_MINUTES)
        session.setSessionExtras(extras)
    }
}
