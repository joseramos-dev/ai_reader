package dev.joseramos.aireader.tts

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import dev.joseramos.aireader.core.common.Log
import dev.joseramos.aireader.core.data.book.BookContentRepository
import dev.joseramos.aireader.core.data.book.BookRepository
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.ReadingPosition
import dev.joseramos.aireader.core.data.book.ReadingPositionRepository
import dev.joseramos.aireader.text.Language
import dev.joseramos.aireader.text.LanguageDetector
import dev.joseramos.aireader.text.SpeechNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class PlaybackStatus { IDLE, LOADING, PLAYING, PAUSED, ENDED }

enum class PlaybackError {
    /** El motor de voz no tiene instalada una voz sin conexión para el idioma del libro. */
    VOICE_MISSING,

    /** El dispositivo no tiene motor de texto a voz. */
    NO_ENGINE,
    TEXT_NOT_READY
}

sealed interface SleepTimer {
    data object Off : SleepTimer

    data class Minutes(val minutes: Int, val endsAt: Long) : SleepTimer

    data object EndOfChapter : SleepTimer
}

data class PlaybackState(
    val bookId: String? = null,
    val bookTitle: String = "",
    val chapterTitle: String? = null,
    val coverPath: String? = null,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val position: ReadingPosition? = null,
    val phrase: String? = null,
    val speed: Float = 1f,
    val sleepTimer: SleepTimer = SleepTimer.Off,
    val error: PlaybackError? = null,
    /** Idioma del libro (el de la voz que hay que instalar si falta). */
    val language: Language = Language.SPANISH
) {
    val isActive: Boolean get() = bookId != null && status != PlaybackStatus.IDLE
    val isPlaying: Boolean get() = status == PlaybackStatus.PLAYING || status == PlaybackStatus.LOADING

    /** Se está escuchando el libro: sonando, preparando la frase o en pausa (no al terminar ni parado). */
    val isListening: Boolean get() = bookId != null && (isPlaying || status == PlaybackStatus.PAUSED)
}

/**
 * Lectura en voz alta de un libro, independiente de la UI (la usa el `PlaybackService`):
 *
 * frases (canal de 3) → síntesis en un único hilo → audio PCM (canal de 2) → AudioTrack.
 *
 * Mientras suena una frase se sintetiza la siguiente. La frase actual (resaltado, posición guardada,
 * temporizador) cambia cuando empieza a *sonar*, no cuando se escribe en el búfer del track. La
 * velocidad se aplica al reproducir, así que cambiarla no reinicia la frase. Saltar (de frase, de
 * página o de capítulo) o cambiar de posición cancela la sesión —se corta la síntesis en curso y se
 * descarta el audio que quedaba en el búfer— y la vuelve a arrancar desde la frase nueva. Si estaba
 * en pausa, sigue en pausa en la frase nueva. La posición se guarda por frase.
 *
 * Las órdenes que cambian de sesión (empezar, saltar, parar) se ejecutan de una en una, en orden, para
 * que varios toques seguidos no dejen dos sesiones sonando a la vez.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // Es la API completa de un reproductor: no gana nada partiéndola.
class PlaybackEngine(
    context: Context,
    private val voice: TtsEngine,
    private val content: BookContentRepository,
    private val books: BookRepository,
    private val positions: ReadingPositionRepository,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state

    private val synthDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val paused = MutableStateFlow(false)
    private val commands = Mutex()
    private var session: Job? = null
    private var sleepJob: Job? = null
    private var sleepChapterEnd: Int? = null
    private var chapters: List<Chapter> = emptyList()
    private var lastSavedAt = 0L

    /** Track de la sesión en curso (para cambiar la velocidad en marcha). */
    @Volatile private var output: SpeechTrack? = null

    /** Si el dispositivo no admite cambiar la velocidad al reproducir, se sintetiza ya a esa velocidad. */
    @Volatile private var speedAtPlayback = true

    /** Idioma detectado de cada libro (se calcula una vez por sesión). */
    private val languages = mutableMapOf<String, Language>()

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aireader:read-aloud")
        .apply { setReferenceCounted(false) }
    private var resumeOnFocusGain = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(speechAudioAttributes)
        .setOnAudioFocusChangeListener(::onFocusChange)
        .build()

    /** Empieza a leer [bookId] desde [from]. */
    suspend fun start(bookId: String, from: ReadingPosition, speed: Float = _state.value.speed) {
        commands.withLock { startLocked(bookId, from, speed, keepPaused = false) }
    }

    /**
     * Si la lectura en curso es de otro libro distinto de [bookId], la para y olvida todo lo suyo
     * (frases, audio pendiente, capítulos y estado), para que no quede nada de él al abrir otro.
     */
    suspend fun releaseOtherBook(bookId: String) {
        commands.withLock {
            val current = _state.value.bookId
            if (current != null && current != bookId) stopLocked()
        }
    }

    private suspend fun startLocked(bookId: String, from: ReadingPosition, speed: Float, keepPaused: Boolean) {
        stopSession()
        // Antes que el estado: un «reanudar» que llegue mientras se prepara la sesión no se pierde.
        paused.value = keepPaused
        // El temporizador de apagado era del libro anterior: no debe pausar el nuevo.
        if (_state.value.bookId != bookId) setSleepTimer(SleepTimer.Off)
        val book = books.getBook(bookId) ?: return
        chapters = content.chapters(bookId)
        val language = languageOf(bookId, from.page)
        _state.value = PlaybackState(
            bookId = bookId,
            bookTitle = book.title,
            chapterTitle = chapterAt(from.page)?.title,
            coverPath = book.coverPath,
            status = if (paused.value) PlaybackStatus.PAUSED else PlaybackStatus.LOADING,
            position = from,
            speed = speed,
            sleepTimer = _state.value.sleepTimer.takeIf { _state.value.bookId == bookId } ?: SleepTimer.Off,
            language = language
        )
        val error = when (voice.load(language)) {
            VoiceAvailability.READY -> null
            VoiceAvailability.MISSING -> PlaybackError.VOICE_MISSING
            VoiceAvailability.NO_ENGINE -> PlaybackError.NO_ENGINE
        }
        if (error != null) {
            _state.update { it.copy(status = PlaybackStatus.IDLE, error = error) }
            return
        }
        if (content.pagesFrom(bookId, 1, 1).isEmpty()) {
            _state.update { it.copy(status = PlaybackStatus.IDLE, error = PlaybackError.TEXT_NOT_READY) }
            return
        }
        if (!paused.value) {
            audioManager.requestAudioFocus(focusRequest)
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        session = scope.launch { runSession(bookId, from, language) }
    }

    fun pause() {
        if (!_state.value.isPlaying) return
        paused.value = true
        _state.update { it.copy(status = PlaybackStatus.PAUSED) }
        releaseWakeLock()
        scope.launch { savePosition(force = true) }
    }

    fun resume() {
        val current = _state.value
        when (current.status) {
            PlaybackStatus.PAUSED -> {
                audioManager.requestAudioFocus(focusRequest)
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
                paused.value = false
                _state.update { it.copy(status = PlaybackStatus.PLAYING) }
            }
            PlaybackStatus.ENDED, PlaybackStatus.IDLE -> {
                val bookId = current.bookId ?: return
                scope.launch { start(bookId, current.position ?: ReadingPosition(1)) }
            }
            else -> Unit
        }
    }

    /** Deja de escuchar: para la sesión, suelta la voz y el foco de audio y vacía el estado. */
    fun stop() {
        scope.launch { commands.withLock { stopLocked() } }
    }

    private suspend fun stopLocked() {
        savePosition(force = true)
        stopSession()
        setSleepTimer(SleepTimer.Off)
        audioManager.abandonAudioFocusRequest(focusRequest)
        voice.release()
        chapters = emptyList()
        paused.value = false
        resumeOnFocusGain = false
        _state.value = PlaybackState(speed = _state.value.speed)
    }

    /** Descarta el error de un intento fallido de empezar (sin tocar una lectura en curso). */
    fun clearError() {
        _state.update {
            if (it.status ==
                PlaybackStatus.IDLE
            ) {
                PlaybackState(speed = it.speed)
            } else {
                it.copy(error = null)
            }
        }
    }

    /** Abandona la frase actual (aunque esté a medias) y pasa a la siguiente. En la última, no hace nada. */
    fun next() = jump { bookId, position -> PhraseSource(content, bookId).next(position) }

    /** Vuelve a la frase anterior (en la primera del libro, la repite). */
    fun previous() = jump { bookId, position -> PhraseSource(content, bookId).previous(position) }

    /** Salta al principio del capítulo siguiente (si lo hay). */
    fun nextChapter() = jump { _, position ->
        chapters.firstOrNull { it.startPage > position.page }?.let { ReadingPosition(it.startPage) }
    }

    /**
     * Como en un reproductor de música: si ya se ha avanzado dentro del capítulo, vuelve a su
     * principio; si se está en su principio, va al capítulo anterior.
     */
    fun previousChapter() = jump { _, position ->
        chapterAt(position.page)?.let { current ->
            val atStart = position == ReadingPosition(current.startPage)
            val target = if (atStart) chapters.lastOrNull { it.startPage < current.startPage } ?: current else current
            ReadingPosition(target.startPage)
        }
    }

    /** Cambia la velocidad en marcha, sin reiniciar la frase (salvo que el dispositivo no lo admita). */
    fun setSpeed(speed: Float) {
        if (speed == _state.value.speed) return
        _state.update { it.copy(speed = speed) }
        if (!_state.value.isListening) return
        val track = output
        if (speedAtPlayback && (track == null || track.setSpeed(speed))) return
        speedAtPlayback = false
        jump { _, position -> position }
    }

    fun setSleepTimer(timer: SleepTimer) {
        sleepJob?.cancel()
        sleepChapterEnd = null
        val applied = when (timer) {
            is SleepTimer.Minutes -> {
                val endsAt = System.currentTimeMillis() + timer.minutes * MINUTE_MS
                sleepJob = scope.launch {
                    delay(timer.minutes * MINUTE_MS)
                    pause()
                    _state.update { it.copy(sleepTimer = SleepTimer.Off) }
                }
                timer.copy(endsAt = endsAt)
            }
            SleepTimer.EndOfChapter -> {
                sleepChapterEnd = _state.value.position?.page?.let(::chapterAt)?.endPage
                timer
            }
            SleepTimer.Off -> timer
        }
        _state.update { it.copy(sleepTimer = applied) }
    }

    /**
     * Reanuda la lectura en la posición que devuelva [target] (`null`: no se mueve). Se calcula ya
     * dentro de la cola de órdenes, a partir de la posición que dejó la orden anterior, para que
     * tocar «siguiente» tres veces avance tres frases.
     */
    private fun jump(target: suspend (bookId: String, position: ReadingPosition) -> ReadingPosition?) {
        scope.launch {
            commands.withLock {
                val current = _state.value
                val bookId = current.bookId ?: return@withLock
                val position = current.position ?: return@withLock
                if (current.status == PlaybackStatus.IDLE) return@withLock
                val to = target(bookId, position) ?: return@withLock
                startLocked(bookId, to, current.speed, keepPaused = current.status == PlaybackStatus.PAUSED)
            }
        }
    }

    private suspend fun runSession(bookId: String, from: ReadingPosition, language: Language) = coroutineScope {
        val texts = Channel<Phrase>(TEXT_BUFFER)
        val audio = Channel<Pair<Phrase, Pcm>>(AUDIO_BUFFER)
        launch {
            PhraseSource(content, bookId).from(from).collect { texts.send(it) }
            texts.close()
        }
        launch(synthDispatcher) {
            try {
                synthesizeAll(texts, audio, language)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Si el motor deja de funcionar, la sesión no debe quedarse colgada en "cargando" para siempre.
                Log.e(TAG, "Fallo sintetizando voz para $bookId", e)
            } finally {
                audio.close()
            }
        }
        val follower = launch(Dispatchers.Default) { followPlayback() }
        withContext(Dispatchers.IO) {
            try {
                play(audio)
                // Las frases que quedaran por marcar (las muy cortas del final) cuentan como leídas.
                follower.cancelAndJoin()
                output?.started(all = true)?.forEach { onPhraseStart(it) }
                _state.update { it.copy(status = PlaybackStatus.ENDED, phrase = null) }
                savePosition(force = true)
                releaseWakeLock()
            } finally {
                // Al saltar o parar se descarta lo que quedaba en el búfer: no debe oírse ni un trozo.
                output?.release()
                output = null
            }
        }
    }

    /**
     * Sintetiza las frases en orden. Una frase que el motor no sepa leer se salta; si fallan varias
     * seguidas, el motor ha dejado de funcionar y se termina.
     */
    private suspend fun synthesizeAll(
        texts: ReceiveChannel<Phrase>,
        audio: Channel<Pair<Phrase, Pcm>>,
        language: Language
    ) {
        val speed = if (speedAtPlayback) 1f else _state.value.speed
        var failures = 0
        for (phrase in texts) {
            val pcm = try {
                voice.synthesize(SpeechNormalizer.normalize(phrase.text, language), speed).also { failures = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                if (++failures >= MAX_CONSECUTIVE_FAILURES) throw e
                Log.w(TAG, "Se salta una frase que la voz no ha podido leer", e)
                continue
            }
            audio.send(phrase to pcm)
        }
    }

    /** Escribe el audio en el track (uno por frecuencia de muestreo) y espera a que acabe de sonar. */
    private suspend fun play(audio: ReceiveChannel<Pair<Phrase, Pcm>>) {
        for ((phrase, pcm) in audio) {
            val track = output?.takeIf { it.sampleRate == pcm.sampleRate } ?: openTrack(pcm.sampleRate)
            track.mark(phrase)
            writeInterruptibly(track, pcm.samples)
        }
        output?.let { drain(it) }
    }

    /** Crea el track de la sesión; si ya había otro (la voz cambió de frecuencia), deja que acabe antes. */
    private suspend fun openTrack(sampleRate: Int): SpeechTrack {
        output?.let { previous ->
            drain(previous)
            previous.started(all = true).forEach { onPhraseStart(it) }
            previous.release()
        }
        val track = SpeechTrack(sampleRate)
        if (speedAtPlayback && !track.setSpeed(_state.value.speed)) {
            // Este dispositivo no cambia la velocidad al reproducir: se vuelve a empezar sintetizándola.
            speedAtPlayback = false
            if (_state.value.speed != 1f) jump { _, position -> position }
        }
        track.track.play()
        output = track
        return track
    }

    /** Marca como actual cada frase cuando empieza a sonar (no cuando se escribe en el búfer). */
    private suspend fun followPlayback() {
        while (currentCoroutineContext().isActive) {
            output?.started()?.forEach { onPhraseStart(it) }
            delay(FOLLOW_INTERVAL_MS)
        }
    }

    private suspend fun onPhraseStart(phrase: Phrase) {
        val end = sleepChapterEnd
        if (end != null && phrase.position.page > end) {
            setSleepTimer(SleepTimer.Off)
            pause()
        }
        _state.update {
            it.copy(
                status = if (paused.value) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING,
                position = phrase.position,
                phrase = phrase.text,
                chapterTitle = chapterAt(phrase.position.page)?.title ?: it.chapterTitle
            )
        }
        savePosition(force = false)
    }

    /** Escribe en trozos de ~0,1 s para que pausar o saltar responda enseguida. */
    private suspend fun writeInterruptibly(track: SpeechTrack, pcm: FloatArray) {
        val slice = (track.sampleRate / SLICES_PER_SECOND).coerceAtLeast(1)
        var offset = 0
        while (offset < pcm.size) {
            currentCoroutineContext().ensureActive()
            waitWhilePaused(track)
            val count = minOf(slice, pcm.size - offset)
            track.write(pcm, offset, count)
            offset += count
        }
    }

    /**
     * Espera a que suene lo que queda en el búfer (respetando la pausa) y para el track. Si el
     * track deja de avanzar sin estar en pausa, no se espera más.
     */
    private suspend fun drain(track: SpeechTrack) {
        var lastPlayed = -1L
        var stalledMs = 0L
        while (track.framesPlayed < track.framesWritten && stalledMs < DRAIN_STALL_MS) {
            currentCoroutineContext().ensureActive()
            waitWhilePaused(track)
            delay(FOLLOW_INTERVAL_MS)
            val played = track.framesPlayed
            stalledMs = if (played == lastPlayed) stalledMs + FOLLOW_INTERVAL_MS else 0L
            lastPlayed = played
        }
        runCatching { track.track.stop() }
    }

    private suspend fun waitWhilePaused(track: SpeechTrack) {
        if (!paused.value) return
        track.track.pause()
        paused.first { !it }
        track.track.play()
    }

    private suspend fun savePosition(force: Boolean) {
        val current = _state.value
        val bookId = current.bookId ?: return
        val position = current.position ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSavedAt < SAVE_INTERVAL_MS) return
        lastSavedAt = now
        // Lo que ya ha sonado cuenta como leído (para las reglas sin spoilers).
        positions.save(bookId, position, markRead = true)
    }

    private suspend fun stopSession() {
        session?.cancelAndJoin()
        session = null
        output = null
        releaseWakeLock()
    }

    /** Idioma del libro, a partir de unas páginas de texto desde [page]. */
    private suspend fun languageOf(bookId: String, page: Int): Language = languages.getOrPut(bookId) {
        val sample = content.pagesFrom(bookId, page, LANGUAGE_SAMPLE_PAGES).flatMap { it.paragraphs }
        LanguageDetector.detect(sample)
    }

    private fun chapterAt(page: Int): Chapter? = chapters.lastOrNull { page >= it.startPage }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Una voz hablando no se «baja de volumen»: se pausa y se reanuda al recuperar el foco.
                if (_state.value.isPlaying) {
                    resumeOnFocusGain = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) {
                resumeOnFocusGain = false
                resume()
            }
        }
    }

    private companion object {
        const val TAG = "PlaybackEngine"
        const val TEXT_BUFFER = 3
        const val LANGUAGE_SAMPLE_PAGES = 8
        const val AUDIO_BUFFER = 2
        const val SLICES_PER_SECOND = 10
        const val SAVE_INTERVAL_MS = 3_000L
        const val MINUTE_MS = 60_000L
        const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L

        /** Cada cuánto se mira qué frase está sonando (y si el track ha terminado). */
        const val FOLLOW_INTERVAL_MS = 40L

        /** Si el track no avanza durante este tiempo sin estar en pausa, se da por terminado. */
        const val DRAIN_STALL_MS = 1_000L

        /** Frases seguidas que pueden fallar antes de dar el motor por estropeado. */
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
