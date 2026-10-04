package dev.joseramos.aireader.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lee el WAV que genera el motor de voz del sistema y lo pasa a PCM float mono. Admite lo que
 * escriben los motores de Android: PCM de 8 o 16 bits y float de 32 bits. Si viene en estéreo,
 * mezcla los canales.
 */
object WavReader {
    fun read(file: File): Pcm = read(file.readBytes())

    fun read(bytes: ByteArray): Pcm {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= RIFF_HEADER && tag(buffer, 0) == "RIFF" && tag(buffer, WAVE_TAG) == "WAVE") {
            "No es un WAV"
        }
        var format: Format? = null
        var offset = RIFF_HEADER
        while (offset + CHUNK_HEADER <= bytes.size) {
            val id = tag(buffer, offset)
            val size = buffer.getInt(offset + CHUNK_SIZE)
            val start = offset + CHUNK_HEADER
            // Algunos motores dejan el tamaño del bloque de datos a 0 o a -1 si escriben en streaming.
            val end = if (size <= 0 || start + size > bytes.size) bytes.size else start + size
            when (id) {
                "fmt " -> format = Format(
                    encoding = buffer.getShort(start + FMT_ENCODING).toInt(),
                    channels = buffer.getShort(start + FMT_CHANNELS).toInt().coerceAtLeast(1),
                    sampleRate = buffer.getInt(start + FMT_SAMPLE_RATE),
                    bits = buffer.getShort(start + FMT_BITS).toInt()
                )
                "data" -> return decode(buffer, start, end, checkNotNull(format) { "WAV sin formato" })
            }
            // Los bloques ocupan siempre un número par de bytes.
            offset = end + (end - start) % 2
        }
        error("WAV sin datos")
    }

    private fun decode(buffer: ByteBuffer, start: Int, end: Int, format: Format): Pcm {
        val sample: (Int) -> Float = when {
            format.encoding == ENCODING_FLOAT && format.bits == Float.SIZE_BITS -> buffer::getFloat
            format.encoding == ENCODING_PCM && format.bits == Short.SIZE_BITS -> { at ->
                buffer.getShort(at) / SHORT_SCALE
            }
            format.encoding == ENCODING_PCM && format.bits == Byte.SIZE_BITS -> { at ->
                ((buffer.get(at).toInt() and BYTE_MASK) - BYTE_OFFSET) / BYTE_OFFSET.toFloat()
            }
            else -> throw IllegalArgumentException("Formato de WAV no admitido: ${format.encoding}/${format.bits} bits")
        }
        val bytesPerSample = format.bits / Byte.SIZE_BITS
        val frames = (end - start) / (bytesPerSample * format.channels)
        val samples = FloatArray(frames)
        var position = start
        for (frame in 0 until frames) {
            var sum = 0f
            repeat(format.channels) {
                sum += sample(position)
                position += bytesPerSample
            }
            samples[frame] = sum / format.channels
        }
        return Pcm(samples, format.sampleRate)
    }

    private fun tag(buffer: ByteBuffer, at: Int) =
        String(ByteArray(TAG_SIZE) { buffer.get(at + it) }, Charsets.US_ASCII)

    private class Format(val encoding: Int, val channels: Int, val sampleRate: Int, val bits: Int)

    // Cabecera RIFF: "RIFF", tamaño, "WAVE"; cada bloque: identificador y tamaño.
    private const val TAG_SIZE = 4
    private const val WAVE_TAG = 8
    private const val RIFF_HEADER = 12
    private const val CHUNK_SIZE = 4
    private const val CHUNK_HEADER = 8

    // Campos del bloque "fmt ".
    private const val FMT_ENCODING = 0
    private const val FMT_CHANNELS = 2
    private const val FMT_SAMPLE_RATE = 4
    private const val FMT_BITS = 14

    private const val ENCODING_PCM = 1
    private const val ENCODING_FLOAT = 3
    private const val SHORT_SCALE = 32_768f
    private const val BYTE_MASK = 0xFF
    private const val BYTE_OFFSET = 128
}
