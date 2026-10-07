package dev.joseramos.aireader.ai.embeddings

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.BreakIterator

/**
 * Normalizador `Precompiled` de SentencePiece (nmt_nfkc): un trie de doble array, sobre bytes
 * UTF-8, que lleva cada carácter (o grafema) a su forma normalizada. Replica el de
 * `tokenizers` de Hugging Face, incluidas sus rarezas: se toma la coincidencia de prefijo más
 * corta y sustituye al grafema entero.
 */
@Suppress("MagicNumber") // Desplazamientos y máscaras del formato binario del trie (Darts-clone).
internal class PrecompiledCharsMap(val blob: ByteArray) {
    private val trie: IntArray
    private val normalized: ByteArray

    init {
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val trieSize = buffer.int
        trie = IntArray(trieSize / 4) { buffer.int }
        normalized = blob.copyOfRange(4 + trieSize, blob.size)
    }

    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        val graphemes = BreakIterator.getCharacterInstance().apply { setText(text) }
        var start = graphemes.first()
        var end = graphemes.next()
        while (end != BreakIterator.DONE) {
            val grapheme = text.substring(start, end)
            val bytes = grapheme.encodeToByteArray()
            val whole = if (bytes.size < MAX_GRAPHEME_BYTES) transform(bytes) else null
            if (whole != null) {
                out.append(whole)
            } else {
                var i = 0
                while (i < grapheme.length) {
                    val next = i + Character.charCount(grapheme.codePointAt(i))
                    val part = grapheme.substring(i, next)
                    out.append(transform(part.encodeToByteArray()) ?: part)
                    i = next
                }
            }
            start = end
            end = graphemes.next()
        }
        return out.toString()
    }

    private fun transform(key: ByteArray): String? {
        val index = firstMatch(key)
        if (index < 0) return null
        var end = index
        while (end < normalized.size && normalized[end] != 0.toByte()) end++
        return normalized.decodeToString(index, end)
    }

    /** Valor de la coincidencia de prefijo más corta de [key] en el trie, o -1. */
    private fun firstMatch(key: ByteArray): Int {
        var pos = offset(trie[0])
        for (b in key) {
            val c = b.toInt() and 0xFF
            if (c == 0) break
            pos = pos xor c
            val unit = trie.getOrNull(pos) ?: return -1
            if (label(unit) != c) return -1
            pos = pos xor offset(unit)
            if (hasLeaf(unit)) return value(trie.getOrNull(pos) ?: return -1)
        }
        return -1
    }

    private fun hasLeaf(unit: Int) = (unit ushr 8) and 1 == 1
    private fun value(unit: Int) = unit and Int.MAX_VALUE
    private fun label(unit: Int) = unit and (Int.MIN_VALUE or 0xFF)
    private fun offset(unit: Int) = (unit ushr 10) shl ((unit and (1 shl 9)) ushr 6)

    private companion object {
        const val MAX_GRAPHEME_BYTES = 6
    }
}
