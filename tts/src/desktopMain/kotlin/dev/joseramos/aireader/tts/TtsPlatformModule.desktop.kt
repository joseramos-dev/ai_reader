package dev.joseramos.aireader.tts

import dev.joseramos.aireader.core.common.IoDispatcher
import java.io.IOException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.roundToInt
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * [AudioSink] sobre una línea de Java Sound (PCM de 16 bits mono). Java Sound no cambia la velocidad sin cambiar el
 * tono, así que [setSpeed] dice que no y el motor de voz sintetiza ya a la velocidad pedida.
 */
internal class JavaSoundSink(override val sampleRate: Int) : AudioSink {
    private val line: SourceDataLine

    init {
        val format = AudioFormat(sampleRate.toFloat(), BITS, 1, true, false)
        line = AudioSystem.getSourceDataLine(format)
        // Medio segundo de búfer: lo bastante para no cortar el audio, y pausar o saltar descarta lo que quede.
        line.open(format, sampleRate * BYTES_PER_SAMPLE / 2)
    }

    override val framesPlayed: Long get() = line.longFramePosition

    override fun write(pcm: FloatArray, offset: Int, count: Int) {
        val bytes = ByteArray(count * BYTES_PER_SAMPLE)
        for (i in 0 until count) {
            val sample = (pcm[offset + i].coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
            bytes[i * BYTES_PER_SAMPLE] = sample.toByte()
            bytes[i * BYTES_PER_SAMPLE + 1] = (sample shr Byte.SIZE_BITS).toByte()
        }
        line.write(bytes, 0, bytes.size)
    }

    override fun setSpeed(speed: Float): Boolean = false

    override fun play() = line.start()

    override fun pause() = line.stop()

    override fun stop() = line.stop()

    override fun release() {
        runCatching {
            line.stop()
            line.flush()
        }
        line.close()
    }

    private companion object {
        const val BITS = 16
        const val BYTES_PER_SAMPLE = 2
    }
}

/** Sin foco de audio en Windows: la voz suena junto a lo demás. */
internal class NoAudioFocus : AudioFocusController {
    override var listener: AudioFocusListener? = null

    override fun request() = Unit

    override fun abandon() = Unit
}

/** Sin wake lock: en Windows la lectura se hace con la ventana abierta. */
internal object NoWakeLock : WakeLock {
    override fun acquire(timeoutMs: Long) = Unit

    override fun release() = Unit
}

/** En Windows basta con hablar con el motor: no hay servicio en primer plano ni sesión multimedia. */
internal class DirectPlaybackBridge(private val engine: PlaybackEngine) : PlaybackBridge {
    override suspend fun started() = Unit

    override suspend fun resume() = engine.resume()
}

/** Abre los ajustes de voz de Windows. */
internal object WindowsVoiceSettings : VoiceSettings {
    override fun installVoice() = openSettings()

    override fun openSettings() {
        try {
            ProcessBuilder("cmd", "/c", "start", "", "ms-settings:speech").start()
        } catch (_: IOException) {
            // Sin Windows (o sin cmd) no hay nada que abrir.
        }
    }
}

actual val ttsPlatformModule: Module = module {
    single { SapiTtsEngine(get(), get(IoDispatcher)) } bind TtsEngine::class
    single<AudioSinkFactory> { AudioSinkFactory { sampleRate -> JavaSoundSink(sampleRate) } }
    single<AudioFocusController> { NoAudioFocus() }
    single<WakeLock> { NoWakeLock }
    single<PlaybackBridge> { DirectPlaybackBridge(get()) }
    single<VoiceSettings> { WindowsVoiceSettings }
}
