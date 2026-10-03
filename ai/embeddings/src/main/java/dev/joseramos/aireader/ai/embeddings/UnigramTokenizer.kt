package dev.joseramos.aireader.ai.embeddings

import java.io.File
import java.util.Base64
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.listSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tokenizador SentencePiece Unigram de XLM-RoBERTa (el de multilingual-e5) en Kotlin puro, leído
 * del `tokenizer.json` de Hugging Face. Sustituye al de DJL, cuya librería nativa no está
 * alineada a páginas de 16 KB. Reproduce el pipeline de `tokenizers`: tokens especiales sobre el
 * texto crudo, normalizador Precompiled + espacios repetidos, pre-tokenizador Metaspace, Viterbi
 * de Unigram (con los desconocidos consecutivos fusionados) y `<s> … </s>` truncando por la cola.
 * Solo admite esa configuración: si el `tokenizer.json` trae otra, falla al cargar.
 */
internal class UnigramTokenizer(
    val charsMap: PrecompiledCharsMap,
    val vocab: Vocab,
    val unkId: Int,
    val bosId: Int,
    val eosId: Int,
    val specialTokens: List<Pair<String, Int>>,
    private val maxLength: Int
) {
    private val specialStarts = specialTokens.map { it.first[0] }.toSet()

    /** IDs de [text] con `<s>` y `</s>`, como mucho [maxLength]. */
    fun encode(text: String): LongArray {
        val ids = IntList()
        var segmentStart = 0
        var i = 0
        while (i < text.length) {
            val special = if (text[i] in specialStarts) {
                specialTokens.filter { text.startsWith(it.first, i) }.maxByOrNull { it.first.length }
            } else {
                null
            }
            if (special == null) {
                i++
                continue
            }
            encodeSegment(text.substring(segmentStart, i), ids)
            ids.add(special.second)
            i += special.first.length
            segmentStart = i
        }
        encodeSegment(text.substring(segmentStart), ids)

        val kept = minOf(ids.size, maxLength - 2)
        return LongArray(kept + 2).also { out ->
            out[0] = bosId.toLong()
            for (t in 0 until kept) out[t + 1] = ids[t].toLong()
            out[kept + 1] = eosId.toLong()
        }
    }

    private fun encodeSegment(segment: String, ids: IntList) {
        if (segment.isEmpty()) return
        val normalized = collapseSpaces(charsMap.normalize(segment))
        if (normalized.isEmpty()) return
        val marked = normalized.replace(' ', METASPACE).let { if (it[0] == METASPACE) it else "$METASPACE$it" }
        // Se parte delante de cada '▁', que queda unido a la palabra que le sigue.
        var start = 0
        for (end in 1..marked.length) {
            if (end == marked.length || marked[end] == METASPACE) {
                viterbi(marked, start, end, ids)
                start = end
            }
        }
    }

    /** Segmentación de mayor puntuación de `text[from, to)`, como `Unigram::encode_optimized`. */
    private fun viterbi(text: String, from: Int, to: Int, ids: IntList) {
        val size = to - from
        val bestScore = DoubleArray(size + 1)
        val bestStart = IntArray(size + 1) { -1 }
        val bestId = IntArray(size + 1)
        var pos = 0
        while (pos < size) {
            val scoreHere = bestScore[pos]
            val charLength = Character.charCount(text.codePointAt(from + pos))
            var hasSingleChar = false
            var hash = 0
            for (length in 1..minOf(vocab.maxPieceLength, size - pos)) {
                hash = Vocab.hashStep(hash, text[from + pos + length - 1])
                val id = vocab.find(text, from + pos, from + pos + length, hash)
                if (id < 0) continue
                val candidate = scoreHere + vocab.scores[id]
                val end = pos + length
                if (bestStart[end] < 0 || candidate > bestScore[end]) {
                    bestScore[end] = candidate
                    bestStart[end] = pos
                    bestId[end] = id
                }
                if (length == charLength) hasSingleChar = true
            }
            if (!hasSingleChar) {
                val end = pos + charLength
                val candidate = scoreHere + vocab.unkScore
                if (bestStart[end] < 0 || candidate > bestScore[end]) {
                    bestScore[end] = candidate
                    bestStart[end] = pos
                    bestId[end] = unkId
                }
            }
            pos += charLength
        }
        emitPieces(text, from, size, bestStart, bestId, ids)
    }

    /** Recorre el mejor camino de atrás adelante, fusionando los desconocidos consecutivos en una sola pieza. */
    private fun emitPieces(text: String, from: Int, size: Int, bestStart: IntArray, bestId: IntArray, ids: IntList) {
        val pieces = ArrayList<IntArray>()
        var end = size
        var unkEnd = -1
        while (end > 0) {
            val start = bestStart[end]
            if (bestId[end] == unkId) {
                if (unkEnd < 0) unkEnd = end
            } else {
                if (unkEnd >= 0) pieces += intArrayOf(end, unkEnd)
                unkEnd = -1
                pieces += intArrayOf(start, end)
            }
            end = start
        }
        if (unkEnd >= 0) pieces += intArrayOf(0, unkEnd)
        for (piece in pieces.asReversed()) {
            val id = vocab.find(text, from + piece[0], from + piece[1])
            ids.add(if (id >= 0) id else unkId)
        }
    }

    private fun collapseSpaces(text: String): String {
        if (!text.contains("  ")) return text
        val out = StringBuilder(text.length)
        for (c in text) if (c != ' ' || out.isEmpty() || out.last() != ' ') out.append(c)
        return out.toString()
    }

    companion object {
        private const val METASPACE = '▁'
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Lee [file]. Con [cache], la primera vez guarda ahí una copia binaria y las siguientes la lee
         * en lugar del JSON (17 MB que hay que recorrer entero): ver [TokenizerCache].
         */
        fun load(file: File, maxLength: Int, cache: File? = null): UnigramTokenizer {
            cache?.let { TokenizerCache.read(it, file, maxLength) }?.let { return it }
            return parse(file, maxLength).also { tokenizer -> cache?.let { TokenizerCache.write(it, file, tokenizer) } }
        }

        @OptIn(ExperimentalSerializationApi::class)
        private fun parse(file: File, maxLength: Int): UnigramTokenizer {
            val config = file.inputStream().buffered().use { json.decodeFromStream<TokenizerConfig>(it) }
            checkPipeline(config)
            val charsMap = config.normalizer["normalizers"]!!.jsonArray[0].jsonObject
                .string("precompiled_charsmap")
                .let { PrecompiledCharsMap(Base64.getDecoder().decode(it)) }
            val special = config.addedTokens.filter { it.special }.map { it.content to it.id }
            val specialIds = config.postProcessor["special_tokens"]!!.jsonObject
            fun specialId(token: String) = specialIds[token]!!.jsonObject["ids"]!!.jsonArray.single().jsonPrimitive.int
            return UnigramTokenizer(
                charsMap = charsMap,
                vocab = config.model.vocab,
                unkId = config.model.unkId,
                bosId = specialId("<s>"),
                eosId = specialId("</s>"),
                specialTokens = special,
                maxLength = maxLength
            )
        }

        private fun checkPipeline(config: TokenizerConfig) {
            val normalizers = config.normalizer["normalizers"]?.jsonArray?.map { it.jsonObject }
            val replace = normalizers?.getOrNull(1)
            check(
                config.normalizer.string("type") == "Sequence" &&
                    normalizers != null &&
                    normalizers.size == 2 &&
                    normalizers[0].string("type") == "Precompiled" &&
                    replace?.string("type") == "Replace" &&
                    replace["pattern"]?.jsonObject?.string("Regex") == " {2,}" &&
                    replace.string("content") == " "
            ) { "Normalizador no soportado: ${config.normalizer}" }

            val pre = config.preTokenizer
            val prepends = pre["prepend_scheme"]?.jsonPrimitive?.contentOrNull?.let { it == "always" }
                ?: (pre["add_prefix_space"]?.jsonPrimitive?.booleanOrNull == true)
            check(
                pre.string("type") == "Metaspace" &&
                    pre.string("replacement") == METASPACE.toString() &&
                    prepends &&
                    pre["split"]?.jsonPrimitive?.booleanOrNull != false
            ) { "Pre-tokenizador no soportado: $pre" }

            val single = config.postProcessor["single"] as? JsonArray
            check(
                config.postProcessor.string("type") == "TemplateProcessing" &&
                    single?.map { it.jsonObject.keys.single() } == listOf("SpecialToken", "Sequence", "SpecialToken")
            ) { "Post-procesador no soportado: ${config.postProcessor}" }

            check(config.model.type == "Unigram" && !config.model.byteFallback) {
                "Modelo no soportado: ${config.model.type}"
            }
        }

        private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.contentOrNull
    }
}

@Serializable
private class TokenizerConfig(
    @SerialName("added_tokens") val addedTokens: List<AddedToken>,
    val normalizer: JsonObject,
    @SerialName("pre_tokenizer") val preTokenizer: JsonObject,
    @SerialName("post_processor") val postProcessor: JsonObject,
    val model: UnigramModel
)

@Serializable
private class AddedToken(val id: Int, val content: String, val special: Boolean = false)

@Serializable
private class UnigramModel(
    val type: String,
    @SerialName("unk_id") val unkId: Int,
    @SerialName("byte_fallback") val byteFallback: Boolean = false,
    @Serializable(with = VocabSerializer::class) val vocab: Vocab
)

/**
 * Las ~250.000 piezas del vocabulario en un solo `String` con una tabla hash de direccionamiento
 * abierto: unos pocos MB, frente a las decenas que ocuparían como `HashMap<String, Int>`.
 */
internal class Vocab(val chars: String, val starts: IntArray, val scores: DoubleArray) {
    val maxPieceLength: Int
    val unkScore: Double
    private val table: IntArray
    private val mask: Int

    init {
        val count = scores.size
        var max = 0
        for (id in 0 until count) max = maxOf(max, starts[id + 1] - starts[id])
        maxPieceLength = max
        // Como `tokenizers`: los desconocidos puntúan 10 por debajo de la peor pieza.
        unkScore = scores.min() - UNK_PENALTY
        table = IntArray(Integer.highestOneBit(maxOf(count, 1) * 2) * 2) { -1 }
        mask = table.size - 1
        // Si una pieza se repite, gana la última, como en el HashMap de `tokenizers`.
        for (id in 0 until count) {
            val from = starts[id]
            val to = starts[id + 1]
            var slot = slotOf(hash(chars, from, to))
            while (table[slot] >= 0 && !matches(table[slot], chars, from, to)) slot = (slot + 1) and mask
            table[slot] = id
        }
    }

    fun find(text: String, from: Int, to: Int): Int = find(text, from, to, hash(text, from, to))

    /** ID de `text[from, to)`, o -1; [hash] es el de [hash] sobre ese rango. */
    fun find(text: String, from: Int, to: Int, hash: Int): Int {
        var slot = slotOf(hash)
        while (true) {
            val id = table[slot]
            if (id < 0 || matches(id, text, from, to)) return id
            slot = (slot + 1) and mask
        }
    }

    private fun matches(id: Int, text: String, from: Int, to: Int): Boolean {
        val start = starts[id]
        val length = starts[id + 1] - start
        return length == to - from && chars.regionMatches(start, text, from, length)
    }

    private fun slotOf(hash: Int): Int = (hash * GOLDEN_RATIO).let { it xor (it ushr MIX_SHIFT) } and mask

    private fun hash(text: String, from: Int, to: Int): Int {
        var h = 0
        for (i in from until to) h = hashStep(h, text[i])
        return h
    }

    companion object {
        private const val UNK_PENALTY = 10.0
        private const val HASH_MULTIPLIER = 31
        private const val GOLDEN_RATIO = -0x61c88647
        private const val MIX_SHIFT = 16

        /** Hash de un rango calculado carácter a carácter, para ir alargando la pieza sin recalcularlo. */
        fun hashStep(hash: Int, c: Char): Int = HASH_MULTIPLIER * hash + c.code
    }
}

/** Lee `[[pieza, puntuación], …]` en streaming, sin crear un objeto por pieza. */
@OptIn(ExperimentalSerializationApi::class)
private object VocabSerializer : KSerializer<Vocab> {
    private val pieceDescriptor = listSerialDescriptor(String.serializer().descriptor)
    override val descriptor = listSerialDescriptor(pieceDescriptor)

    override fun deserialize(decoder: Decoder): Vocab {
        val chars = StringBuilder()
        val starts = IntList().apply { add(0) }
        var scores = DoubleArray(INITIAL_CAPACITY)
        var count = 0
        // Cada pieza es un array [texto, puntuación].
        val piece = object : DeserializationStrategy<Unit> {
            override val descriptor = pieceDescriptor
            override fun deserialize(decoder: Decoder) = decoder.decodeStructure(pieceDescriptor) {
                check(decodeElementIndex(pieceDescriptor) == 0)
                chars.append(decodeStringElement(pieceDescriptor, 0))
                check(decodeElementIndex(pieceDescriptor) == 1)
                if (count == scores.size) scores = scores.copyOf(count * 2)
                scores[count++] = decodeDoubleElement(pieceDescriptor, 1)
                check(decodeElementIndex(pieceDescriptor) == CompositeDecoder.DECODE_DONE)
                starts.add(chars.length)
            }
        }
        decoder.decodeStructure(descriptor) {
            while (true) {
                val index = decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                decodeSerializableElement(descriptor, index, piece)
            }
        }
        return Vocab(chars.toString(), starts.toArray(), scores.copyOf(count))
    }

    override fun serialize(encoder: Encoder, value: Vocab) = throw UnsupportedOperationException()

    private const val INITIAL_CAPACITY = 1024
}

/** Lista de `Int` sin boxing. */
internal class IntList {
    private var items = IntArray(16)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == items.size) items = items.copyOf(size * 2)
        items[size++] = value
    }

    operator fun get(index: Int): Int = items[index]

    fun toArray(): IntArray = items.copyOf(size)
}
