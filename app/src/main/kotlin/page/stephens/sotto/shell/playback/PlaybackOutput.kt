package page.stephens.sotto.shell.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import java.io.File

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
}

internal class Media3PlaybackOutput(private val player: Player) : PlaybackOutput {
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
}
