package dev.joseramos.aireader.spikes.playback

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Player mínimo de Media3 que delega el audio en [AudioEngine]. */
@OptIn(UnstableApi::class)
class SpikePlayer(context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val engine = AudioEngine(context)
    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE

    private val item = MediaItemData.Builder("spike")
        .setMediaItem(MediaItem.Builder().setMediaId("spike").build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Prueba de audio en segundo plano")
                .setArtist("AI Reader · Spikes")
                .build()
        )
        .build()

    override fun getState(): State = State.Builder()
        .setAvailableCommands(
            Player.Commands.Builder().addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_RELEASE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE
            ).build()
        )
        .setPlaylist(listOf(item))
        .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setPlaybackState(playbackState)
        .build()

    override fun handlePrepare(): ListenableFuture<*> {
        playbackState = Player.STATE_READY
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (playWhenReady) {
            playbackState = Player.STATE_READY
            engine.play()
        } else {
            engine.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine.stop()
        playWhenReady = false
        playbackState = Player.STATE_IDLE
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        engine.stop()
        return Futures.immediateVoidFuture()
    }
}
