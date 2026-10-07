package dev.joseramos.aireader.core.data.book

import dev.joseramos.aireader.core.common.Log
import dev.joseramos.aireader.core.common.currentTimeMillis
import dev.joseramos.aireader.core.data.db.BookDao
import dev.joseramos.aireader.core.data.db.BookEntity
import dev.joseramos.aireader.core.data.db.DocumentType
import dev.joseramos.aireader.core.data.db.DocumentTypeSource
import dev.joseramos.aireader.core.data.db.IndexStatus
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Libro tal como lo ve la UI. Las páginas son en base 1. */
data class Book(
    val id: String,
    val title: String,
    val author: String?,
    val filePath: String,
    val pageCount: Int,
    val coverPath: String?,
    val importedAt: Long,
    val lastOpenedAt: Long?,
    val indexStatus: IndexStatus,
    val indexProgress: Float,
    /** Página donde se quedó la lectura, o `null` si nunca se ha abierto. */
    val currentPage: Int? = null,
    /** `null` hasta que la indexación lo clasifica. */
    val documentType: DocumentType? = null,
    val documentTypeSource: DocumentTypeSource = DocumentTypeSource.AUTO,
    /** Página del marcapáginas más reciente, si hay alguno (solo en la lista de la biblioteca). */
    val lastBookmarkPage: Int? = null
) {
    val isLiterature: Boolean get() = documentType == DocumentType.LITERATURE

    val readingProgress: Float
        get() = if (pageCount > 0 && currentPage != null) currentPage.toFloat() / pageCount else 0f
}

class BookRepository(private val bookDao: BookDao, private val io: CoroutineDispatcher) {
    fun observeBooks(): Flow<List<Book>> =
        bookDao.observeAll().map { rows -> rows.map { it.book.toBook(it.currentPage, it.lastBookmarkPage) } }

    fun observeBook(id: String): Flow<Book?> = bookDao.observe(id).map { it?.toBook() }

    suspend fun getBook(id: String): Book? = bookDao.get(id)?.toBook()

    suspend fun markOpened(id: String) = bookDao.markOpened(id, currentTimeMillis())

    /** Tipo elegido por el usuario: la indexación ya no lo vuelve a calcular. */
    suspend fun setDocumentType(id: String, type: DocumentType) =
        bookDao.updateDocumentType(id, type, DocumentTypeSource.USER)

    /**
     * Borra el libro con todos sus datos (en cascada) y sus ficheros (PDF y portada). En Windows no se puede borrar un
     * fichero abierto (el PDF, si el lector o la indexación aún lo tienen): lo que quede lo borra el barrido de
     * ficheros huérfanos al arrancar.
     */
    suspend fun deleteBook(id: String) = withContext(io) {
        val book = bookDao.get(id) ?: return@withContext
        bookDao.delete(id)
        listOfNotNull(book.filePath, book.coverPath).map(::File).filter { it.exists() && !it.delete() }.forEach {
            Log.w(TAG, "No se pudo borrar ${it.name}: se borrará al volver a abrir la app")
        }
    }

    private companion object {
        const val TAG = "BookRepository"
    }
}

private fun BookEntity.toBook(currentPage: Int? = null, lastBookmarkPage: Int? = null) = Book(
    id = id,
    title = title,
    author = author,
    filePath = filePath,
    pageCount = pageCount,
    coverPath = coverPath,
    importedAt = importedAt,
    lastOpenedAt = lastOpenedAt,
    indexStatus = indexStatus,
    indexProgress = indexProgress,
    currentPage = currentPage,
    documentType = documentType,
    documentTypeSource = documentTypeSource,
    lastBookmarkPage = lastBookmarkPage
)
