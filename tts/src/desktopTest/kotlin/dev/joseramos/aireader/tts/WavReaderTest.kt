package dev.joseramos.aireader.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WavReaderTest {
    @Test
    fun `lee PCM de 16 bits mono`() {
        val pcm = WavReader.read(
            wav(sampleRate = 24_000, channels = 1, bits = 16, encoding = 1) {
                putShorts(0, 16_384, -32_768)
            }
        )

        assertEquals(24_000, pcm.sampleRate)
        assertArrayEquals(floatArrayOf(0f, 0.5f, -1f), pcm.samples, 1e-6f)
    }

    @Test
    fun `lee float de 32 bits`() {
        val pcm = WavReader.read(
            wav(sampleRate = 22_050, channels = 1, bits = 32, encoding = 3) {
                putFloats(0.25f, -0.75f)
            }
        )

        assertEquals(22_050, pcm.sampleRate)
        assertArrayEquals(floatArrayOf(0.25f, -0.75f), pcm.samples, 1e-6f)
    }

    @Test
    fun `mezcla el estereo a mono`() {
        val pcm = WavReader.read(
            wav(sampleRate = 16_000, channels = 2, bits = 16, encoding = 1) {
                putShorts(16_384, 0)
            }
        )

        assertArrayEquals(floatArrayOf(0.25f), pcm.samples, 1e-6f)
    }

    @Test
    fun `salta los bloques que no son de audio`() {
        val pcm = WavReader.read(
            wav(sampleRate = 24_000, channels = 1, bits = 16, encoding = 1, extraChunk = "LIST") { putShorts(16_384) }
        )

        assertArrayEquals(floatArrayOf(0.5f), pcm.samples, 1e-6f)
    }

    @Test
    fun `acepta un bloque de datos sin tamano (escrito en streaming)`() {
        val bytes = wav(sampleRate = 24_000, channels = 1, bits = 16, encoding = 1) { putShorts(16_384, 16_384) }
        // El tamaño del bloque "data" está justo antes de las muestras.
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size - 4 - 4, 0)

        assertArrayEquals(floatArrayOf(0.5f, 0.5f), WavReader.read(bytes).samples, 1e-6f)
    }

    @Test
    fun `rechaza lo que no es un WAV`() {
        assertThrows(IllegalArgumentException::class.java) { WavReader.read(ByteArray(64)) }
    }

    private class Samples {
        val buffer: ByteBuffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)

        fun putShorts(vararg values: Int) = values.forEach { buffer.putShort(it.toShort()) }

        fun putFloats(vararg values: Float) = values.forEach { buffer.putFloat(it) }
    }

    private fun wav(
        sampleRate: Int,
        channels: Int,
        bits: Int,
        encoding: Int,
        extraChunk: String? = null,
        samples: Samples.() -> Unit
    ): ByteArray {
        val data = Samples().apply(samples).buffer.let { it.array().copyOf(it.position()) }
        val extra = if (extraChunk != null) 8 + 4 else 0
        val out = ByteBuffer.allocate(12 + 8 + 16 + extra + 8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(out.capacity() - 8).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16)
            .putShort(encoding.toShort()).putShort(channels.toShort()).putInt(sampleRate)
            .putInt(sampleRate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        if (extraChunk != null) out.put(extraChunk.toByteArray()).putInt(4).putInt(0)
        out.put("data".toByteArray()).putInt(data.size).put(data)
        return out.array()
    }
}
