package dev.joseramos.aireader.indexing

import dev.joseramos.aireader.core.common.AppDirs
import dev.joseramos.aireader.core.common.Log
import dev.joseramos.aireader.core.common.PickedFile
import dev.joseramos.aireader.core.common.currentTimeMillis
import dev.joseramos.aireader.core.data.db.BookDao
import dev.joseramos.aireader.core.data.db.BookEntity
import dev.joseramos.aireader.core.data.db.IndexStatus
import dev.joseramos.aireader.pdf.PdfRendererFactory
import java.io.File
import java.io.IOException
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class ImportException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Importa un PDF elegido por el usuario: lo copia al almacenamiento de la app (para no depender de
 * permisos sobre el original), genera la portada, lo registra y encola su indexación.
 */
class BookImporter(
    private val dirs: AppDirs,
    private val renderers: PdfRendererFactory,
    private val bookDao: BookDao,
    private val scheduler: IndexScheduler,
    private val io: CoroutineDispatcher
) {
    /**
     * Devuelve el id del libro importado. Cualquier fallo llega como [ImportException], con un mensaje para el
     * usuario, y sin dejar en disco el PDF copiado ni la portada.
     */
    suspend fun import(picked: PickedFile): String = withContext(io) {
        val id = Uuid.random().toString()
        val fileName = picked.name ?: DEFAULT_NAME
        val pdf = File(dirs.files, "$BOOKS_DIR/$id.pdf").apply { parentFile?.mkdirs() }
        val cover = File(dirs.files, "$COVERS_DIR/$id.png")
        try {
            copy(picked, pdf)
            val pageCount = cover(pdf, cover)
            bookDao.upsert(
                BookEntity(
                    id = id,
                    title = fileName.substringBeforeLast('.').ifBlank { fileName },
                    author = null,
                    fileName = fileName,
                    filePath = pdf.absolutePath,
                    pageCount = pageCount,
                    coverPath = cover.absolutePath,
                    importedAt = currentTimeMillis(),
                    lastOpenedAt = null,
                    indexStatus = IndexStatus.PENDING,
                    indexProgress = 0f,
                    cleanerVersion = 0
                )
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            pdf.delete()
            cover.delete()
            throw when (e) {
                is ImportException, is CancellationException, is Error -> e
                else -> ImportException("No se pudo importar el PDF.", e)
            }
        }
        scheduler.enqueue(id)
        id
    }

    /**
     * Borra los PDF y portadas que no son de ningún libro: los que no se pudieron borrar con su libro (en Windows un
     * fichero abierto no se puede borrar) o los de una importación cortada. Respeta los recientes, que pueden ser de
     * una importación en curso.
     */
    suspend fun deleteOrphanFiles() = withContext(io) {
        val ids = bookDao.allIds().toSet()
        val cutoff = currentTimeMillis() - ORPHAN_MIN_AGE_MS
        listOf(BOOKS_DIR, COVERS_DIR)
            .flatMap { File(dirs.files, it).listFiles()?.toList().orEmpty() }
            .filter { it.isFile && it.nameWithoutExtension !in ids && it.lastModified() < cutoff }
            .forEach { file ->
                if (file.delete()) {
                    Log.i(TAG, "Borrado ${file.parentFile?.name}/${file.name}, que no era de ningún libro")
                } else {
                    Log.w(TAG, "No se pudo borrar ${file.parentFile?.name}/${file.name}, que no es de ningún libro")
                }
            }
    }

    private fun copy(picked: PickedFile, pdf: File) {
        val input = try {
            picked.openStream()
        } catch (e: IOException) {
            throw ImportException(CANNOT_OPEN, e)
        } ?: throw ImportException(CANNOT_OPEN)
        // Un fallo al copiar (disco lleno…) lo traduce import() como cualquier otro.
        input.use { source -> pdf.outputStream().use { source.copyTo(it) } }
    }

    /** Guarda la portada y devuelve el número de páginas. PDFBox puede fallar con excepciones de todo tipo. */
    private fun cover(pdf: File, cover: File): Int = try {
        renderers.writeCover(pdf, cover, COVER_WIDTH_PX)
    } catch (e: SecurityException) {
        throw ImportException("El PDF está protegido con contraseña.", e)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        throw ImportException("El archivo no es un PDF válido.", e)
    }

    companion object {
        private const val TAG = "BookImporter"
        private const val CANNOT_OPEN = "No se pudo abrir el archivo."
        private const val ORPHAN_MIN_AGE_MS = 10 * 60 * 1000L
        private const val BOOKS_DIR = "books"
        private const val COVERS_DIR = "covers"
        private const val DEFAULT_NAME = "Documento.pdf"
        private const val COVER_WIDTH_PX = 360
    }
}
