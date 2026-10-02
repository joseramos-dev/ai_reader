package dev.joseramos.aireader.spikes.embeddings

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Embeddings con ONNX Runtime + tokenizador DJL. Funciona con e5 (mean pooling sobre
 * last_hidden_state) y con EmbeddingGemma (salida sentence_embedding ya agrupada).
 */
class OnnxEmbedder(dir: File, threads: Int) : Closeable {
    val isGemma = dir.name.startsWith("gemma")
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer: HuggingFaceTokenizer

    init {
        val model = dir.listFiles { f -> f.name.endsWith(".onnx") }!!.first()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(model.absolutePath, options)
        tokenizer = HuggingFaceTokenizer.builder()
            .optTokenizerPath(File(dir, "tokenizer.json").toPath())
            .optMaxLength(512)
            .optTruncation(true)
            .optPadding(false)
            .build()
    }

    val inputNames: Set<String> get() = session.inputNames
    val outputNames: Set<String> get() = session.outputNames

    fun query(text: String) = if (isGemma) "task: search result | query: $text" else "query: $text"

    fun document(text: String) = if (isGemma) "title: none | text: $text" else "passage: $text"

    /** Devuelve los embeddings normalizados L2 y el número total de tokens procesados. */
    fun embed(texts: List<String>): Pair<List<FloatArray>, Int> {
        val encodings = texts.map { tokenizer.encode(it) }
        val batch = encodings.size
        val seq = encodings.maxOf { it.ids.size }
        val ids = LongArray(batch * seq)
        val mask = LongArray(batch * seq)
        encodings.forEachIndexed { b, e ->
            e.ids.copyInto(ids, b * seq)
            e.attentionMask.copyInto(mask, b * seq)
        }
        val shape = longArrayOf(batch.toLong(), seq.toLong())
        val tensors = mutableMapOf<String, OnnxTensor>()
        try {
            for (name in session.inputNames) {
                val data = when (name) {
                    "input_ids" -> ids
                    "attention_mask" -> mask
                    "token_type_ids" -> LongArray(batch * seq)
                    else -> error("Entrada desconocida del modelo: $name")
                }
                tensors[name] = OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)
            }
            session.run(tensors).use { result ->
                val vectors = if ("sentence_embedding" in session.outputNames) {
                    @Suppress("UNCHECKED_CAST")
                    (result.get("sentence_embedding").get().value as Array<FloatArray>).toList()
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val hidden = result.get(0).value as Array<Array<FloatArray>>
                    hidden.mapIndexed { b, tokens -> meanPool(tokens, mask, b * seq) }
                }
                return vectors.map { normalize(it) } to encodings.sumOf { it.ids.size }
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    private fun meanPool(tokens: Array<FloatArray>, mask: LongArray, offset: Int): FloatArray {
        val out = FloatArray(tokens[0].size)
        var count = 0
        tokens.forEachIndexed { t, vector ->
            if (mask[offset + t] == 1L) {
                count++
                for (d in out.indices) out[d] += vector[d]
            }
        }
        for (d in out.indices) out[d] /= count
        return out
    }

    private fun normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / norm }
    }

    override fun close() {
        session.close()
        tokenizer.close()
    }
}

fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}
