package dev.joseramos.aireader.tts

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * `Player` de Media3 que expone el [PlaybackEngine] a la sesión multimedia: notificación,
 * pantalla de bloqueo, auriculares y Bluetooth. «Adelante/atrás» saltan de frase.
 */
@OptIn(UnstableApi::class)
class ReaderPlayer(private val engine: PlaybackEngine) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch { engine.state.collect { invalidateState() } }
    }

    override fun getState(): State {
        val playback = engine.state.value
        val builder = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_PREPARE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_RELEASE
                ).build()
            )
        if (playback.bookId == null) {
            return builder.setPlaybackState(
                Player.STATE_IDLE
            ).setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(playback.bookTitle)
            .setArtist(playback.chapterTitle)
            .setDisplayTitle(playback.bookTitle)
            .setSubtitle(playback.phrase)
            .apply { playback.coverPath?.let { setArtworkUri(Uri.fromFile(File(it))) } }
            .build()
        val item = MediaItemData.Builder(playback.bookId)
            .setMediaItem(MediaItem.Builder().setMediaId(playback.bookId).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .build()
        val state = when (playback.status) {
            PlaybackStatus.IDLE -> Player.STATE_IDLE
            PlaybackStatus.LOADING -> Player.STATE_BUFFERING
            PlaybackStatus.PLAYING, PlaybackStatus.PAUSED -> Player.STATE_READY
            PlaybackStatus.ENDED -> Player.STATE_ENDED
        }
        return builder
            .setPlaylist(listOf(item))
            .setPlaybackState(state)
            .setPlayWhenReady(playback.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) engine.resume() else engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        engine.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        // Adelante/atrás (botones de la notificación) saltan frases; siguiente/anterior (auriculares,
        // coche) saltan capítulos, como las pistas de un audiolibro.
        when (seekCommand) {
            in FORWARD_COMMANDS -> engine.next()
            in BACKWARD_COMMANDS -> engine.previous()
            in NEXT_COMMANDS -> engine.nextChapter()
            in PREVIOUS_COMMANDS -> engine.previousChapter()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        val FORWARD_COMMANDS = setOf(Player.COMMAND_SEEK_FORWARD)
        val BACKWARD_COMMANDS = setOf(Player.COMMAND_SEEK_BACK)
        val NEXT_COMMANDS = setOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        val PREVIOUS_COMMANDS = setOf(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
    }
}
