package dev.joseramos.aireader.tts

import dev.joseramos.aireader.core.data.book.ReadingPosition
import dev.joseramos.aireader.core.data.book.ReadingPositionRepository
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Punto de entrada de la UI a la lectura en voz alta: delega en el motor y, para lo que depende de la plataforma
 * (en Android, la sesión multimedia que mantiene la lectura en primer plano), en el [PlaybackBridge].
 */
class PlaybackController(
    private val engine: PlaybackEngine,
    private val positions: ReadingPositionRepository,
    private val settings: SettingsRepository,
    private val bridge: PlaybackBridge
) {
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
            bridge.resume()
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
        if (engine.state.value.error == null) bridge.started()
    }

    /** Lee [bookId] desde una frase concreta (por ejemplo, al tocarla en el modo texto). */
    suspend fun playFrom(bookId: String, position: ReadingPosition) {
        val speed = settings.settings.first().readingSpeed
        engine.start(bookId, position, speed)
        if (engine.state.value.error == null) bridge.started()
    }

    /**
     * Al abrir el libro [bookId]: si sonaba otro, se para y se vacía todo lo suyo (frases, audio
     * pendiente, estado del mini reproductor y notificación), para que no quede nada del anterior.
     */
    suspend fun onBookOpened(bookId: String) = engine.releaseOtherBook(bookId)

    fun pause() = engine.pause()

    suspend fun resume() = bridge.resume()

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
}
