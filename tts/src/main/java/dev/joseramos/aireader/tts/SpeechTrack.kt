package dev.joseramos.aireader.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dev.joseramos.aireader.core.common.Log

/** Atributos de la voz (los usan el track y la petición de foco de audio). */
internal val speechAudioAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/**
 * `AudioTrack` de una sesión de lectura. Lleva la cuenta de lo escrito y de dónde empieza cada
 * frase, para saber qué frase está sonando de verdad (el búfer del track va por delante de lo que
 * se oye). La velocidad se aplica al reproducir, sin cambiar el tono.
 */
internal class SpeechTrack(val sampleRate: Int) {
    val track: AudioTrack = newTrack(sampleRate)

    /** Frames escritos en el track desde que se creó. */
    @Volatile var framesWritten = 0L
        private set

    /** Frase y frame en el que empieza, en el orden en que se escribieron. */
    private val marks = ArrayDeque<Pair<Long, Phrase>>()

    /** Lo que ya ha sonado, en frames (el contador del track es de 32 bits sin signo). */
    val framesPlayed: Long get() = track.playbackHeadPosition.toLong() and UINT_MASK

    /** Anota que [phrase] empieza en lo siguiente que se escriba. */
    fun mark(phrase: Phrase) = synchronized(marks) { marks.addLast(framesWritten to phrase) }

    fun write(pcm: FloatArray, offset: Int, count: Int) {
        track.write(pcm, offset, count, AudioTrack.WRITE_BLOCKING)
        framesWritten += count
    }

    /** Frases que han empezado a sonar desde la última consulta (o todas las anotadas si [all]). */
    fun started(all: Boolean = false): List<Phrase> {
        val played = framesPlayed
        return synchronized(marks) {
            buildList {
                while (marks.isNotEmpty() && (all || marks.first().first <= played)) add(marks.removeFirst().second)
            }
        }
    }

    /** `false` si el dispositivo no admite cambiar la velocidad al reproducir. */
    fun setSpeed(speed: Float): Boolean = runCatching {
        track.playbackParams = track.playbackParams.setSpeed(speed).setPitch(1f)
    }.onFailure { Log.w(TAG, "No se puede cambiar la velocidad del audio", it) }.isSuccess

    fun release() {
        runCatching {
            track.pause()
            track.flush()
        }
        track.release()
    }

    private companion object {
        const val TAG = "SpeechTrack"
        const val UINT_MASK = 0xFFFF_FFFFL
        const val BUFFER_MULTIPLIER = 4

        fun newTrack(sampleRate: Int): AudioTrack {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            )
            return AudioTrack.Builder()
                .setAudioAttributes(speechAudioAttributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuffer * BUFFER_MULTIPLIER)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }
    }
}
