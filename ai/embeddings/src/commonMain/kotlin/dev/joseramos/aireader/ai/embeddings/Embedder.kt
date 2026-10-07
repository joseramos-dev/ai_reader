package dev.joseramos.aireader.ai.embeddings

import java.nio.ByteBuffer
import java.nio.ByteOrder

class EmbeddingModelMissingException : IllegalStateException("El modelo de embeddings no está descargado")

/** Convierte textos en vectores normalizados (L2) para la búsqueda semántica del RAG. */
interface Embedder {
    /** Identificador del modelo: los vectores de modelos distintos no son comparables. */
    val modelId: String
    val dimensions: Int

    suspend fun isAvailable(): Boolean

    suspend fun embedDocuments(texts: List<String>): List<FloatArray>

    suspend fun embedQuery(text: String): FloatArray

    /** Libera el modelo de la memoria (se vuelve a cargar al usarlo). */
    suspend fun release()
}

/** Serialización de vectores float32 en little-endian, como se guardan en Room. */
object VectorCodec {
    fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(vector)
        return buffer.array()
    }

    fun decodeInto(bytes: ByteArray, target: FloatArray, offset: Int) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(
            target,
            offset,
            bytes.size / Float.SIZE_BYTES
        )
    }
}
