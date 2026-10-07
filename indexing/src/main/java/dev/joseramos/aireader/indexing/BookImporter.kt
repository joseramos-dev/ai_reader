package dev.joseramos.aireader.indexing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.joseramos.aireader.core.common.currentTimeMillis
import dev.joseramos.aireader.core.data.db.BookDao
import dev.joseramos.aireader.core.data.db.BookEntity
import dev.joseramos.aireader.core.data.db.IndexStatus
import dev.joseramos.aireader.pdf.PdfPageRenderer
import java.io.File
import java.io.IOException
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class ImportException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Importa un PDF elegido por el usuario: lo copia al almacenamiento interno (para no depender de
 * permisos sobre el original), genera la portada, lo registra y encola su indexación.
 */
class BookImporter(
    private val context: Context,
    private val bookDao: BookDao,
    private val scheduler: IndexScheduler,
    private val io: CoroutineDispatcher
) {
    /** Devuelve el id del libro importado. */
    suspend fun import(uri: Uri): String = withContext(io) {
        val id = Uuid.random().toString()
        val fileName = displayName(uri) ?: DEFAULT_NAME
        val pdf = File(context.filesDir, "$BOOKS_DIR/$id.pdf").apply { parentFile?.mkdirs() }
        val input =
            context.contentResolver.openInputStream(uri) ?: throw ImportException("No se pudo abrir el archivo.")
        input.use { source -> pdf.outputStream().use { source.copyTo(it) } }

        val cover = File(context.filesDir, "$COVERS_DIR/$id.png")
        val pageCount = try {
            PdfPageRenderer.writeCover(pdf, cover, COVER_WIDTH_PX)
        } catch (e: IOException) {
            pdf.delete()
            throw ImportException("El archivo no es un PDF válido.", e)
        } catch (e: SecurityException) {
            pdf.delete()
            throw ImportException("El PDF está protegido con contraseña.", e)
        }

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
        scheduler.enqueue(id)
        id
    }

    private fun displayName(uri: Uri): String? = context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    companion object {
        private const val BOOKS_DIR = "books"
        private const val COVERS_DIR = "covers"
        private const val DEFAULT_NAME = "Documento.pdf"
        private const val COVER_WIDTH_PX = 360
    }
}
