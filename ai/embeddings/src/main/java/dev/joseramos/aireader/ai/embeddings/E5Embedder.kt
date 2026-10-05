package dev.joseramos.aireader.ai.embeddings

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Process
import android.system.Os
import dev.joseramos.aireader.ai.models.ModelCatalog
import dev.joseramos.aireader.ai.models.ModelManager
import dev.joseramos.aireader.core.common.DefaultDispatcher
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * multilingual-e5-small (int8, 384 dimensiones) con ONNX Runtime. e5 exige los prefijos
 * `query:` y `passage:`; el vector es la media de los tokens (mean pooling) normalizada.
 *
 * Cada inferencia usa un solo hilo, y [embedDocuments] reparte los textos de uno en uno entre tantos
 * hilos como núcleos. En un Helio G99 (2 núcleos rápidos y 6 lentos) es 2,5 veces más rápido que una
 * inferencia de 4 hilos con lotes de 16, y con la mitad de memoria: los núcleos rápidos no esperan a los
 * lentos en cada operación, no se calcula relleno y trabajan todos los núcleos.
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

    /** Llamadas que están usando [loaded]: [release] espera a que terminen para cerrarlo. */
    private var users = 0
    private var releaseRequested = false

    private class Loaded(
        val session: OrtSession,
        val tokenizer: UnigramTokenizer,
        /** Hilos de [embedDocuments]; se crean al usarlos por primera vez. */
        val workers: ExecutorCoroutineDispatcher
    )

    override suspend fun isAvailable(): Boolean = models.installedDir(modelId) != null

    override suspend fun embedDocuments(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        return withModel { model ->
            withContext(model.workers) { texts.map { async { embed(model, "passage: $it") } }.awaitAll() }
        }
    }

    // Una pregunta es corta: con un hilo tarda lo mismo que con cuatro (unos 30 ms en un Helio G99).
    override suspend fun embedQuery(text: String): FloatArray =
        withModel { model -> withContext(dispatcher) { embed(model, "query: $text") } }

    override suspend fun release() = mutex.withLock {
        if (users == 0) close() else releaseRequested = true
    }

    /**
     * Ejecuta [block] con el modelo cargado. Varias llamadas pueden usarlo a la vez (una sesión de ONNX
     * Runtime lo admite): así el chat puede preguntar mientras se indexa un libro sin esperar a que
     * termine cada grupo de fragmentos.
     */
    private suspend fun <T> withModel(block: suspend (Loaded) -> T): T {
        val model = mutex.withLock {
            withContext(dispatcher) { load() }.also {
                users++
                releaseRequested = false
            }
        }
        try {
            return block(model)
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    users--
                    if (users == 0 && releaseRequested) close()
                }
            }
        }
    }

    private fun close() {
        loaded?.let {
            it.workers.close()
            it.session.close()
        }
        loaded = null
        releaseRequested = false
    }

    private suspend fun load(): Loaded {
        loaded?.let { return it }
        val dir = models.installedDir(modelId) ?: throw EmbeddingModelMissingException()
        // Sin telemetría de ONNX Runtime aunque una versión futura la active sin el provider que quita el manifiesto
        // de :app. Es su desactivación documentada: la librería nativa lee la variable al iniciarse, así que va antes
        // de tocar ORT. setTelemetry(false) no bastaría: el evento de arranque ya se ha emitido al crear el entorno.
        Os.setenv("ORT_DISABLE_TELEMETRY", "1", true)
        val options = OrtSession.SessionOptions().apply {
            // Un hilo por inferencia: el paralelismo lo dan varios textos a la vez (ver la clase).
            setIntraOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Sin el arena allocator de ONNX Runtime: aquí no acelera (medido en un Helio G99) y
            // reserva memoria de más.
            setMemoryPatternOptimization(false)
            setCPUArenaAllocator(false)
        }
        val session = OrtEnvironment.getEnvironment().createSession(File(dir, "model.onnx").absolutePath, options)
        // Si el tokenizer.json no se puede leer, no se deja la sesión abierta.
        val tokenizer = runCatching {
            UnigramTokenizer.load(File(dir, "tokenizer.json"), MAX_TOKENS, cache = File(dir, "tokenizer.cache"))
        }.getOrElse {
            session.close()
            throw it
        }
        return Loaded(session, tokenizer, workerThreads()).also { loaded = it }
    }

    /**
     * Un hilo por núcleo (como mucho [MAX_WORKERS], para acotar la memoria), con prioridad de segundo
     * plano para que la lectura no dé tirones mientras se indexa. En las pruebas no restó velocidad.
     */
    private fun workerThreads(): ExecutorCoroutineDispatcher {
        val count = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_WORKERS)
        val number = AtomicInteger()
        return Executors.newFixedThreadPool(count) { task ->
            Thread(
                {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    task.run()
                },
                "e5-embedder-${number.incrementAndGet()}"
            )
        }.asCoroutineDispatcher()
    }

    /** Vector de [text]. Va solo en la inferencia, así que no hay relleno y cuentan todos sus tokens. */
    private fun embed(model: Loaded, text: String): FloatArray {
        val ids = model.tokenizer.encode(text)
        val shape = longArrayOf(1, ids.size.toLong())
        val env = OrtEnvironment.getEnvironment()
        val inputs = model.session.inputNames.associateWith { name ->
            val data = when (name) {
                "input_ids" -> ids
                "attention_mask" -> LongArray(ids.size) { 1L }
                else -> LongArray(ids.size) // token_type_ids
            }
            OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)
        }
        try {
            model.session.run(inputs).use { result ->
                return normalize(meanPool((result.get(0) as OnnxTensor).floatBuffer, ids.size))
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /** Media de los vectores de los [tokens] de [hidden], que tiene forma `[1, tokens, DIMENSIONS]`. */
    private fun meanPool(hidden: FloatBuffer, tokens: Int): FloatArray {
        val out = FloatArray(DIMENSIONS)
        for (t in 0 until tokens) {
            for (d in 0 until DIMENSIONS) out[d] += hidden.get(t * DIMENSIONS + d)
        }
        for (d in out.indices) out[d] /= tokens
        return out
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0f } ?: 1f
        return FloatArray(vector.size) { vector[it] / norm }
    }

    private companion object {
        const val DIMENSIONS = 384
        const val MAX_TOKENS = 512

        /** Con 8 inferencias a la vez, el pico de memoria fue de unos 420 MB en un Helio G99. */
        const val MAX_WORKERS = 8
    }
}
