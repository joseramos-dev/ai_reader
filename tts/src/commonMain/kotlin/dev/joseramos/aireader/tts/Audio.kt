package dev.joseramos.aireader.tts

/**
 * Salida de audio de una sesión de lectura: PCM float mono a una frecuencia fija. Cada plataforma pone la suya
 * (`AudioTrack` en Android, Java Sound en Windows).
 */
interface AudioSink {
    val sampleRate: Int

    /** Lo que ya ha sonado, en frames. */
    val framesPlayed: Long

    /** Escribe [count] muestras de [pcm] desde [offset]; espera a que quepan en el búfer. */
    fun write(pcm: FloatArray, offset: Int, count: Int)

    /** `false` si no se puede cambiar la velocidad al reproducir (el motor sintetizará ya a esa velocidad). */
    fun setSpeed(speed: Float): Boolean

    fun play()

    fun pause()

    fun stop()

    /** Descarta lo que quedara en el búfer y libera la salida. */
    fun release()
}

/** Abre una [AudioSink] para una frecuencia de muestreo. */
fun interface AudioSinkFactory {
    fun open(sampleRate: Int): AudioSink
}

/** Avisos del sistema sobre el foco de audio (otra app que suena, una llamada). */
interface AudioFocusListener {
    /** Otra app se queda con el audio para siempre: hay que pausar. */
    fun onLoss()

    /** Otra app lo pide un momento: se pausa y se reanuda al recuperarlo. */
    fun onTransientLoss()

    fun onGain()
}

/** Pide y suelta el foco de audio. En Windows no hace nada. */
interface AudioFocusController {
    var listener: AudioFocusListener?

    fun request()

    fun abandon()
}

/** Evita que el equipo se duerma mientras se lee con la pantalla apagada. En Windows no hace nada. */
interface WakeLock {
    fun acquire(timeoutMs: Long)

    fun release()
}

/**
 * Lo que cambia entre plataformas al ordenar al reproductor: en Android hay que pasar por la sesión multimedia
 * (que arranca el servicio en primer plano); en Windows basta con hablar con el motor.
 */
interface PlaybackBridge {
    /** El motor acaba de empezar una sesión: que suene (y, en Android, que el servicio pase a primer plano). */
    suspend fun started()

    suspend fun resume()
}

/** Abre las pantallas del sistema para instalar voces o elegir el motor de texto a voz. */
interface VoiceSettings {
    /** Abre el instalador de voces del motor; si no lo tiene, los ajustes de voz. */
    fun installVoice()

    /** Abre los ajustes de voz del sistema. */
    fun openSettings()
}
