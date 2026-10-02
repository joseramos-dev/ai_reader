package dev.joseramos.aireader.spikes.common

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

object AudioOut {
    fun newTrack(sampleRate: Int): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** Reproduce las muestras y bloquea hasta terminar. */
    fun playBlocking(samples: FloatArray, sampleRate: Int) {
        val track = newTrack(sampleRate)
        try {
            track.play()
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            Thread.sleep(300)
            track.stop()
        } finally {
            track.release()
        }
    }
}
