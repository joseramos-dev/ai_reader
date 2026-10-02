package dev.joseramos.aireader.tts

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.joseramos.aireader.core.data.book.ReadingPosition
import dev.joseramos.aireader.core.data.book.ReadingPositionRepository
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await

/**
 * Punto de entrada de la UI a la lectura en voz alta. Conecta un `MediaController` con el
 * [PlaybackService] (lo que lo arranca y lo pasa a primer plano al sonar) y delega en el motor.
 */
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: PlaybackEngine,
    private val positions: ReadingPositionRepository,
    private val settings: SettingsRepository
) {
    private var controller: MediaController? = null

    val state: StateFlow<PlaybackState> = engine.state

    /**
     * Lee [bookId] desde [page] (base 1). Si es la página guardada, retoma en la frase exacta;
     * si el libro ya está sonando en pausa, simplemente reanuda.
     */
    suspend fun play(bookId: String, page: Int?) {
        val current = engine.state.value
        if (current.bookId == bookId &&
            current.status == PlaybackStatus.PAUSED &&
            (page == null || page == current.position?.page)
        ) {
            connect().play()
            return
        }
        val saved = positions.get(bookId)
        val from = when {
            page == null -> saved ?: ReadingPosition(1)
            saved?.page == page -> saved
            else -> ReadingPosition(page)
        }
        val speed = settings.settings.first().readingSpeed
        engine.start(bookId, from, speed)
        if (engine.state.value.error == null) connect().play()
    }

    /** Lee [bookId] desde una frase concreta (por ejemplo, al tocarla en el modo texto). */
    suspend fun playFrom(bookId: String, position: ReadingPosition) {
        val speed = settings.settings.first().readingSpeed
        engine.start(bookId, position, speed)
        if (engine.state.value.error == null) connect().play()
    }

    fun pause() = engine.pause()

    suspend fun resume() {
        connect().play()
    }

    fun stop() = engine.stop()

    fun clearError() = engine.clearError()

    fun next() = engine.next()

    fun previous() = engine.previous()

    fun nextChapter() = engine.nextChapter()

    fun previousChapter() = engine.previousChapter()

    suspend fun setSpeed(speed: Float) {
        settings.setReadingSpeed(speed)
        engine.setSpeed(settings.settings.first().readingSpeed)
    }

    fun setSleepTimer(timer: SleepTimer) = engine.setSleepTimer(timer)

    suspend fun preview(text: String): Boolean = engine.preview(text)

    private suspend fun connect(): MediaController {
        controller?.takeIf { it.isConnected }?.let { return it }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        return MediaController.Builder(context, token).buildAsync().await().also { controller = it }
    }
}
