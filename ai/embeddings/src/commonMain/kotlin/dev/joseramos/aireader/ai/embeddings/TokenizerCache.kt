package dev.joseramos.aireader.ai.embeddings

import dev.joseramos.aireader.core.common.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * Copia binaria de un [UnigramTokenizer] ya leído: vocabulario (texto de las piezas, posiciones y
 * puntuaciones), normalizador y tokens especiales, tal cual están en memoria. Leerla es copiar unos
 * pocos MB, frente a recorrer los 17 MB del `tokenizer.json` cada vez que se abre el chat. Es válida
 * mientras el JSON no cambie (mismo tamaño y fecha) y el formato sea este ([FORMAT]); si no, se ignora.
 */
internal object TokenizerCache {
    private const val TAG = "TokenizerCache"
    private const val FORMAT = 1
    private const val BUFFER_BYTES = 1 shl 20

    fun read(cache: File, source: File, maxLength: Int): UnigramTokenizer? {
        if (!cache.exists()) return null
        return runCatching {
            DataInputStream(cache.inputStream().buffered(BUFFER_BYTES)).use { input ->
                if (input.readInt() != FORMAT) return null
                if (input.readLong() != source.length() || input.readLong() != source.lastModified()) return null
                val unkId = input.readInt()
                val bosId = input.readInt()
                val eosId = input.readInt()
                val special = List(input.readInt()) { input.readUTF() to input.readInt() }
                val charsMap = PrecompiledCharsMap(input.readBytes(input.readInt()))
                val count = input.readInt()
                val chars = ByteBuffer.wrap(
                    input.readBytes(input.readInt() * Char.SIZE_BYTES)
                ).asCharBuffer().toString()
                val starts = IntArray(count + 1).also {
                    ByteBuffer.wrap(input.readBytes(it.size * Int.SIZE_BYTES)).asIntBuffer().get(it)
                }
                val scores = DoubleArray(count).also {
                    ByteBuffer.wrap(input.readBytes(it.size * Double.SIZE_BYTES)).asDoubleBuffer().get(it)
                }
                UnigramTokenizer(charsMap, Vocab(chars, starts, scores), unkId, bosId, eosId, special, maxLength)
            }
        }.onFailure { Log.w(TAG, "Caché del tokenizador ilegible: se vuelve a leer el JSON", it) }.getOrNull()
    }

    /** Escribe en un fichero temporal y lo renombra: una copia a medias nunca pasa por buena. */
    fun write(cache: File, source: File, tokenizer: UnigramTokenizer) {
        val temporary = File(cache.parentFile, "${cache.name}.tmp")
        runCatching {
            DataOutputStream(temporary.outputStream().buffered(BUFFER_BYTES)).use { output ->
                output.writeInt(FORMAT)
                output.writeLong(source.length())
                output.writeLong(source.lastModified())
                output.writeInt(tokenizer.unkId)
                output.writeInt(tokenizer.bosId)
                output.writeInt(tokenizer.eosId)
                output.writeInt(tokenizer.specialTokens.size)
                tokenizer.specialTokens.forEach { (content, id) ->
                    output.writeUTF(content)
                    output.writeInt(id)
                }
                val blob = tokenizer.charsMap.blob
                output.writeInt(blob.size)
                output.write(blob)
                val vocab = tokenizer.vocab
                output.writeInt(vocab.scores.size)
                output.writeInt(vocab.chars.length)
                output.write(
                    ByteBuffer.allocate(vocab.chars.length * Char.SIZE_BYTES)
                        .also { it.asCharBuffer().put(vocab.chars) }.array()
                )
                output.write(
                    ByteBuffer.allocate(vocab.starts.size * Int.SIZE_BYTES)
                        .also { it.asIntBuffer().put(vocab.starts) }.array()
                )
                output.write(
                    ByteBuffer.allocate(vocab.scores.size * Double.SIZE_BYTES)
                        .also { it.asDoubleBuffer().put(vocab.scores) }.array()
                )
            }
            check(temporary.renameTo(cache) || (cache.delete() && temporary.renameTo(cache)))
        }.onFailure {
            temporary.delete()
            Log.w(TAG, "No se pudo guardar la caché del tokenizador", it)
        }
    }

    private fun DataInputStream.readBytes(size: Int): ByteArray = ByteArray(size).also { readFully(it) }
}
