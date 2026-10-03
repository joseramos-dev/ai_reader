package dev.joseramos.aireader.ai.embeddings

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.joseramos.aireader.ai.models.ModelCatalog
import dev.joseramos.aireader.ai.models.ModelManager
import dev.joseramos.aireader.core.common.DefaultDispatcher
import java.io.File
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * multilingual-e5-small (int8, 384 dimensiones) con ONNX Runtime. e5 exige los prefijos
 * `query:` y `passage:`; el vector es la media de los tokens (mean pooling) normalizada.
 */
@Singleton
class E5Embedder @Inject constructor(
    private val models: ModelManager,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher
) : Embedder {
    override val modelId: String = ModelCatalog.e5Small.id
    override val dimensions: Int = DIMENSIONS

    private val mutex = Mutex()
    private var loaded: Loaded? = null

    private class Loaded(val session: OrtSession, val tokenizer: UnigramTokenizer)

    override suspend fun isAvailable(): Boolean = models.installedDir(modelId) != null

    override suspend fun embedDocuments(texts: List<String>): List<FloatArray> =
        texts.chunked(BATCH).flatMap { batch -> run(batch.map { "passage: $it" }) }

    override suspend fun embedQuery(text: String): FloatArray = run(listOf("query: $text")).single()

    override suspend fun release() = mutex.withLock {
        loaded?.session?.close()
        loaded = null
    }

    private suspend fun run(texts: List<String>): List<FloatArray> = mutex.withLock {
        withContext(dispatcher) { embed(load(), texts) }
    }

    private suspend fun load(): Loaded {
        loaded?.let { return it }
        val dir = models.installedDir(modelId) ?: throw EmbeddingModelMissingException()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(THREADS)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // El chat es lo único que carga este modelo: en un móvil pesa más la memoria que la
            // latencia de unas pocas preguntas, así que se desactiva el arena allocator de ONNX
            // Runtime (reserva memoria de más para ir rápido en ráfagas largas de inferencia).
            setMemoryPatternOptimization(false)
            setCPUArenaAllocator(false)
        }
        val session = OrtEnvironment.getEnvironment().createSession(File(dir, "model.onnx").absolutePath, options)
        // Si el tokenizer.json no se puede leer, no se deja la sesión abierta.
        val tokenizer = runCatching {
            UnigramTokenizer.load(File(dir, "tokenizer.json"), MAX_TOKENS)
        }.getOrElse {
            session.close()
            throw it
        }
        return Loaded(session, tokenizer).also { loaded = it }
    }

    private fun embed(model: Loaded, texts: List<String>): List<FloatArray> {
        val env = OrtEnvironment.getEnvironment()
        val encodings = texts.map { model.tokenizer.encode(it) }
        val batch = encodings.size
        val length = encodings.maxOf { it.size }
        val ids = LongArray(batch * length)
        val mask = LongArray(batch * length)
        encodings.forEachIndexed { b, encoding ->
            encoding.copyInto(ids, b * length)
            mask.fill(1L, b * length, b * length + encoding.size)
        }
        val shape = longArrayOf(batch.toLong(), length.toLong())
        val inputs = model.session.inputNames.associateWith { name ->
            val data = when (name) {
                "input_ids" -> ids
                "attention_mask" -> mask
                else -> LongArray(batch * length) // token_type_ids
            }
            OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)
        }
        try {
            model.session.run(inputs).use { result ->
                @Suppress("UNCHECKED_CAST")
                val hidden = result.get(0).value as Array<Array<FloatArray>>
                return hidden.mapIndexed { b, tokens -> normalize(meanPool(tokens, mask, b * length)) }
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    private fun meanPool(tokens: Array<FloatArray>, mask: LongArray, offset: Int): FloatArray {
        val out = FloatArray(tokens.first().size)
        var count = 0
        tokens.forEachIndexed { t, vector ->
            if (mask[offset + t] == 1L) {
                count++
                for (d in out.indices) out[d] += vector[d]
            }
        }
        if (count > 0) for (d in out.indices) out[d] /= count
        return out
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0f } ?: 1f
        return FloatArray(vector.size) { vector[it] / norm }
    }

    private companion object {
        const val DIMENSIONS = 384
        const val BATCH = 16
        const val MAX_TOKENS = 512
        const val THREADS = 4
    }
}
