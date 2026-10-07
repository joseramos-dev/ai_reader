package dev.joseramos.aireader.tts

import dev.joseramos.aireader.text.Language
import java.util.Locale

/** Audio de una frase: PCM float mono a [sampleRate]. */
class Pcm(val samples: FloatArray, val sampleRate: Int)

/** Si se puede leer en un idioma con el motor de voz. */
enum class VoiceAvailability {
    READY,

    /** Hay motor, pero no tiene instalada una voz sin conexión para ese idioma. */
    MISSING,

    /** El dispositivo no tiene ningún motor de texto a voz (o no arranca). */
    NO_ENGINE
}

/** Motor de voz. La interfaz existe para poder probar el pipeline con un motor simulado. */
interface TtsEngine {
    /** Prepara la voz de [language] (y suelta la que hubiera). */
    suspend fun load(language: Language): VoiceAvailability

    /** Si [language] se puede leer, sin cambiar la voz cargada. */
    suspend fun availability(language: Language): VoiceAvailability

    /**
     * Sintetiza [text] a velocidad normal (la velocidad se aplica al reproducir). Es cancelable:
     * al cancelar, la síntesis en curso se detiene enseguida.
     */
    suspend fun synthesize(text: String, speed: Float = 1f): Pcm

    fun release()
}

/** Locale con el que se pide la voz de cada idioma (se prefiere ese país si hay varias). */
val Language.locale: Locale
    get() = when (this) {
        Language.SPANISH -> Locale.forLanguageTag("es-ES")
        Language.ENGLISH -> Locale.US
    }
