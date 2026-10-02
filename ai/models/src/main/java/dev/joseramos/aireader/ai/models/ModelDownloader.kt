package dev.joseramos.aireader.ai.models

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request

class ChecksumMismatchException(expected: String, actual: String) :
    IOException("La descarga está corrupta (sha256 esperado $expected, obtenido $actual)")

/**
 * Descarga un fichero con reanudación: lo escribe en `<destino>.part` y, si la descarga se
 * interrumpe, la siguiente pide solo lo que falta con una cabecera `Range`. Al terminar
 * verifica el sha256 y solo entonces lo renombra al destino.
 */
class ModelDownloader @Inject constructor(private val client: OkHttpClient) {

    /** Descarga [url] en [target]. [onProgress] recibe los bytes descargados y el total esperado. */
    suspend fun download(
        url: String,
        target: File,
        sha256: String,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")
        fetchInto(url, partial, onProgress)
        verify(partial, sha256)
        if (!partial.renameTo(target)) throw IOException("No se pudo mover ${partial.name}")
    }

    private suspend fun fetchInto(url: String, partial: File, onProgress: (Long, Long) -> Unit) {
        val offset = if (partial.exists()) partial.length() else 0L
        val request = Request.Builder().url(url).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Error HTTP ${response.code} descargando $url")
            // Si el servidor ignora el Range (200 en vez de 206) se empieza de cero.
            val start = if (response.code == HTTP_PARTIAL) offset else 0L
            val total = start + response.body.contentLength().coerceAtLeast(0)
            FileOutputStream(partial, start > 0).use { out ->
                response.body.byteStream().use { input ->
                    copy(input, out, start) { onProgress(it, total) }
                }
            }
        }
    }

    private suspend fun copy(input: InputStream, out: FileOutputStream, start: Long, onCopied: (Long) -> Unit) {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = start
        var read = input.read(buffer)
        while (read >= 0) {
            coroutineContext.ensureActive()
            out.write(buffer, 0, read)
            copied += read
            onCopied(copied)
            read = input.read(buffer)
        }
    }

    private fun verify(file: File, expected: String) {
        val actual = sha256Of(file)
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete()
            throw ChecksumMismatchException(expected, actual)
        }
    }

    companion object {
        private const val HTTP_PARTIAL = 206
        private const val BUFFER_SIZE = 256 * 1024

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var read = input.read(buffer)
                while (read >= 0) {
                    digest.update(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
