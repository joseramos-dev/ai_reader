package dev.joseramos.aireader.ai.models

import dev.joseramos.aireader.core.data.db.ModelKind

/** Fichero descargable de un modelo, con su sha256 para verificarlo. */
data class ModelFile(val url: String, val name: String, val sha256: String, val sizeBytes: Long)

/** Modelo que la app descarga o trae empaquetado; sus ficheros se guardan tal cual en su directorio. */
data class ModelInfo(
    val id: String,
    val kind: ModelKind,
    val displayName: String,
    val version: String,
    val files: List<ModelFile>
) {
    val sizeBytes: Long get() = files.sumOf { it.sizeBytes }
}

object ModelCatalog {
    /** Versión fijada del repositorio para que los sha256 no cambien. */
    private const val E5_REPO = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/" +
        "761b726dd34fb83930e26aab4e9ac3899aa1fa78"

    /** Embeddings del RAG: multilingual-e5-small cuantizado a int8 (384 dimensiones). */
    val e5Small = ModelInfo(
        id = "emb-multilingual-e5-small-int8",
        kind = ModelKind.EMBEDDING,
        displayName = "Modelo para preguntar al libro",
        version = "761b726",
        files = listOf(
            ModelFile(
                url = "$E5_REPO/onnx/model_quantized.onnx",
                name = "model.onnx",
                sha256 = "f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193",
                sizeBytes = 118_308_185
            ),
            ModelFile(
                url = "$E5_REPO/tokenizer.json",
                name = "tokenizer.json",
                sha256 = "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39",
                sizeBytes = 17_082_730
            )
        )
    )

    val all: List<ModelInfo> = listOf(e5Small)

    fun byId(id: String): ModelInfo = all.first { it.id == id }
}
