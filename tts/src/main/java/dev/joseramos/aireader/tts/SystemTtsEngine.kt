package dev.joseramos.aireader.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dev.joseramos.aireader.core.common.Log
import dev.joseramos.aireader.text.Language
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Locale con el que se pide la voz de cada idioma (se prefiere ese país si hay varias). */
val Language.locale: Locale
    get() = when (this) {
        Language.SPANISH -> Locale.forLanguageTag("es-ES")
        Language.ENGLISH -> Locale.US
    }

/**
 * Voz del sistema (`android.speech.tts`, normalmente Google TTS). Cada frase se sintetiza a un WAV
 * temporal con `synthesizeToFile`, que se lee como PCM y se borra: así el pipeline sigue teniendo
 * el audio en la mano (resaltado exacto, pausa sin perder nada, velocidad al reproducir).
 *
 * Solo se usan voces sin conexión: si el idioma no tiene ninguna instalada, [load] devuelve
 * [VoiceAvailability.MISSING] y la app ofrece instalarla desde el sistema.
 */
class SystemTtsEngine(private val context: Context, private val io: CoroutineDispatcher) : TtsEngine {
    private val mutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val ids = AtomicLong()
    private val dir = File(context.cacheDir, "tts")

    @Volatile private var tts: TextToSpeech? = null
    private var loaded: Language? = null
    private var lastSampleRate = DEFAULT_SAMPLE_RATE

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit

        override fun onDone(utteranceId: String) {
            pending.remove(utteranceId)?.complete(true)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            pending.remove(utteranceId)?.complete(false)
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            pending.remove(utteranceId)?.complete(false)
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            pending.remove(utteranceId)?.complete(false)
        }
    }

    override suspend fun load(language: Language): VoiceAvailability = mutex.withLock {
        val engine = engine() ?: return@withLock VoiceAvailability.NO_ENGINE
        if (loaded == language) return@withLock VoiceAvailability.READY
        when (val choice = choose(engine, language)) {
            is Choice.UseVoice -> if (engine.setVoice(choice.voice) != TextToSpeech.SUCCESS) {
                return@withLock VoiceAvailability.MISSING
            }
            is Choice.UseLocale -> if (engine.setLanguage(choice.locale) < TextToSpeech.LANG_AVAILABLE) {
                return@withLock VoiceAvailability.MISSING
            }
            Choice.Missing -> return@withLock VoiceAvailability.MISSING
        }
        loaded = language
        VoiceAvailability.READY
    }

    override suspend fun availability(language: Language): VoiceAvailability = mutex.withLock {
        val engine = engine() ?: return@withLock VoiceAvailability.NO_ENGINE
        if (choose(engine, language) == Choice.Missing) VoiceAvailability.MISSING else VoiceAvailability.READY
    }

    override suspend fun synthesize(text: String, speed: Float): Pcm {
        val engine = checkNotNull(tts) { "La voz no está cargada" }
        val id = "phrase-${ids.incrementAndGet()}"
        val file = File(dir, "$id.wav")
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        try {
            withContext(io) { dir.mkdirs() }
            engine.setSpeechRate(speed)
            val input = text.take(TextToSpeech.getMaxSpeechInputLength())
            check(engine.synthesizeToFile(input, Bundle(), file, id) == TextToSpeech.SUCCESS) {
                "El motor de voz rechazó la frase"
            }
            val ok = try {
                done.await()
            } catch (e: CancellationException) {
                // Saltar o parar no espera a que termine la frase: se corta la síntesis en curso.
                engine.stop()
                throw e
            }
            check(ok) { "El motor de voz no pudo sintetizar la frase" }
            return withContext(io) {
                // Una frase sin nada pronunciable («—», «…») puede dejar el WAV vacío.
                if (file.length() <= WAV_HEADER_BYTES) {
                    Pcm(FloatArray(0), lastSampleRate)
                } else {
                    WavReader.read(file).also { lastSampleRate = it.sampleRate }
                }
            }
        } finally {
            pending.remove(id)
            withContext(io + NonCancellable) { file.delete() }
        }
    }

    override fun release() {
        pending.values.forEach { it.complete(false) }
        pending.clear()
        tts?.shutdown()
        tts = null
        loaded = null
        dir.deleteRecursively()
    }

    /** Arranca el motor la primera vez; `null` si el dispositivo no tiene ninguno o no responde. */
    private suspend fun engine(): TextToSpeech? {
        tts?.let { return it }
        val status = CompletableDeferred<Int>()
        // El motor se conecta por un servicio: se crea en el hilo principal, que es donde avisa.
        val created = withContext(Dispatchers.Main) { TextToSpeech(context) { status.complete(it) } }
        val result = withTimeoutOrNull(INIT_TIMEOUT_MS) { status.await() }
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "No hay motor de voz disponible (estado $result)")
            created.shutdown()
            return null
        }
        created.setOnUtteranceProgressListener(listener)
        tts = created
        return created
    }

    private sealed interface Choice {
        data class UseVoice(val voice: Voice) : Choice

        data class UseLocale(val locale: Locale) : Choice

        data object Missing : Choice
    }

    /**
     * La mejor voz sin conexión ya instalada del idioma: del país preferido, de más calidad y con
     * menos latencia. Si el motor no informa de sus voces, se usa el idioma directamente.
     */
    private fun choose(engine: TextToSpeech, language: Language): Choice {
        val wanted = language.locale
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        if (voices.isEmpty()) {
            val available = engine.isLanguageAvailable(wanted) >= TextToSpeech.LANG_AVAILABLE
            return if (available) Choice.UseLocale(wanted) else Choice.Missing
        }
        val best = voices
            .filter { voice ->
                voice.locale.language == wanted.language &&
                    !voice.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()
            }
            .maxWithOrNull(
                compareBy<Voice>({ it.locale.country == wanted.country }, { it.quality }, { -it.latency })
            )
        return best?.let(Choice::UseVoice) ?: Choice.Missing
    }

    private companion object {
        const val TAG = "SystemTtsEngine"
        const val DEFAULT_SAMPLE_RATE = 24_000
        const val INIT_TIMEOUT_MS = 10_000L
        const val WAV_HEADER_BYTES = 44L
    }
}
