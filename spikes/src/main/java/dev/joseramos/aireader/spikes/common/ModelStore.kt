package dev.joseramos.aireader.spikes.common

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Modelo descargable: un .tar.bz2 que se extrae, o una lista de ficheros sueltos. */
sealed class ModelSpec(val id: String, val label: String, val sizeMb: Int) {
    class TarBz2(id: String, label: String, sizeMb: Int, val url: String, val dirName: String) :
        ModelSpec(id, label, sizeMb)

    class Files(id: String, label: String, sizeMb: Int, val files: List<Pair<String, String>>) :
        ModelSpec(id, label, sizeMb)
}

object Catalog {
    private const val SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
    private const val E5 = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main"
    private const val GEMMA = "https://huggingface.co/onnx-community/embeddinggemma-300m-ONNX/resolve/main"

    /** La única voz de la app (ver docs/02-diseno-tecnico.md §5.1). */
    val piperDavefxInt8 = ModelSpec.TarBz2(
        "piper-davefx-int8",
        "Piper · es_ES davefx medium int8",
        21,
        "$SHERPA/vits-piper-es_ES-davefx-medium-int8.tar.bz2",
        "vits-piper-es_ES-davefx-medium-int8"
    )

    val e5Small = ModelSpec.Files(
        "e5-small",
        "multilingual-e5-small · int8 (384 dim)",
        135,
        listOf("$E5/onnx/model_quantized.onnx" to "model.onnx", "$E5/tokenizer.json" to "tokenizer.json")
    )
    val gemmaQ4 = ModelSpec.Files(
        "gemma-q4",
        "EmbeddingGemma-300M · q4 (768 dim)",
        230,
        listOf(
            "$GEMMA/onnx/model_q4.onnx" to "model_q4.onnx",
            "$GEMMA/onnx/model_q4.onnx_data" to "model_q4.onnx_data",
            "$GEMMA/tokenizer.json" to "tokenizer.json"
        )
    )
    val gemmaInt8 = ModelSpec.Files(
        "gemma-int8",
        "EmbeddingGemma-300M · int8 (768 dim)",
        343,
        listOf(
            "$GEMMA/onnx/model_quantized.onnx" to "model_quantized.onnx",
            "$GEMMA/onnx/model_quantized.onnx_data" to "model_quantized.onnx_data",
            "$GEMMA/tokenizer.json" to "tokenizer.json"
        )
    )
    val embeddings = listOf(e5Small, gemmaQ4, gemmaInt8)

    val tokenizerE5 = ModelSpec.Files(
        "tok-e5",
        "Tokenizador e5-small",
        17,
        listOf(
            "$E5/tokenizer.json" to "tokenizer.json"
        )
    )
    val tokenizerGemma = ModelSpec.Files(
        "tok-gemma",
        "Tokenizador EmbeddingGemma",
        33,
        listOf(
            "$GEMMA/tokenizer.json" to "tokenizer.json"
        )
    )
}

object ModelStore {
    private fun root(context: Context) = File(context.filesDir, "models").also { it.mkdirs() }

    fun dir(context: Context, spec: ModelSpec): File = when (spec) {
        is ModelSpec.TarBz2 -> File(root(context), spec.dirName)
        is ModelSpec.Files -> File(root(context), spec.id)
    }

    fun isReady(context: Context, spec: ModelSpec) = File(dir(context, spec), ".ready").exists()

    /** Descarga (y extrae si hace falta) el modelo. [onProgress] recibe 0..1 y una descripción. */
    suspend fun download(context: Context, spec: ModelSpec, onProgress: (Float, String) -> Unit) =
        withContext(Dispatchers.IO) {
            val dir = dir(context, spec)
            when (spec) {
                is ModelSpec.TarBz2 -> {
                    val archive = File(root(context), "${spec.dirName}.tar.bz2")
                    fetch(spec.url, archive) { onProgress(it * 0.8f, "Descargando") }
                    onProgress(0.8f, "Extrayendo (puede tardar un poco)")
                    extractTarBz2(archive, root(context))
                    archive.delete()
                }
                is ModelSpec.Files -> {
                    dir.mkdirs()
                    spec.files.forEachIndexed { i, (url, name) ->
                        fetch(url, File(dir, name)) { p -> onProgress((i + p) / spec.files.size, "Descargando $name") }
                    }
                }
            }
            File(dir, ".ready").writeText("ok")
            onProgress(1f, "Listo")
        }

    private suspend fun fetch(url: String, target: File, onProgress: (Float) -> Unit) {
        val partial = File(target.path + ".part")
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        try {
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            partial.renameTo(target)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractTarBz2(archive: File, destination: File) {
        val base = destination.canonicalPath
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered(1 shl 20))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val out = File(destination, entry.name)
                require(out.canonicalPath.startsWith(base)) { "Entrada fuera del destino: ${entry.name}" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                }
            }
        }
    }
}
