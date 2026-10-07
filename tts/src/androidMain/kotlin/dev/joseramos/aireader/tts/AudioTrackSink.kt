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

/** [AudioSink] sobre un `AudioTrack` en streaming. La velocidad se cambia sin tocar el tono. */
internal class AudioTrackSink(override val sampleRate: Int) : AudioSink {
    private val track: AudioTrack = newTrack(sampleRate)

    /** Lo que ya ha sonado, en frames (el contador del track es de 32 bits sin signo). */
    override val framesPlayed: Long get() = track.playbackHeadPosition.toLong() and UINT_MASK

    override fun write(pcm: FloatArray, offset: Int, count: Int) {
        track.write(pcm, offset, count, AudioTrack.WRITE_BLOCKING)
    }

    override fun setSpeed(speed: Float): Boolean = runCatching {
        track.playbackParams = track.playbackParams.setSpeed(speed).setPitch(1f)
    }.onFailure { Log.w(TAG, "No se puede cambiar la velocidad del audio", it) }.isSuccess

    override fun play() = track.play()

    override fun pause() = track.pause()

    override fun stop() {
        runCatching { track.stop() }
    }

    override fun release() {
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
