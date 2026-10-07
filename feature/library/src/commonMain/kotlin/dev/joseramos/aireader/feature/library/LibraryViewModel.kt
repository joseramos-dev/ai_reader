package dev.joseramos.aireader.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.joseramos.aireader.core.common.PickedFile
import dev.joseramos.aireader.core.data.book.Book
import dev.joseramos.aireader.core.data.book.BookRepository
import dev.joseramos.aireader.core.data.db.DocumentType
import dev.joseramos.aireader.indexing.BookImporter
import dev.joseramos.aireader.indexing.ImportException
import dev.joseramos.aireader.indexing.IndexScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SortMode { RECENT, TITLE }

data class LibraryUiState(
    val loading: Boolean = true,
    val books: List<Book> = emptyList(),
    val sort: SortMode = SortMode.RECENT,
    val importing: Boolean = false,
    val error: String? = null
)

class LibraryViewModel(
    private val repository: BookRepository,
    private val importer: BookImporter,
    private val scheduler: IndexScheduler,
    private val savedState: SavedStateHandle
) : ViewModel() {
    private val transient = MutableStateFlow(LibraryUiState(sort = savedState[KEY_SORT] ?: SortMode.RECENT))

    val state: StateFlow<LibraryUiState> = combine(repository.observeBooks(), transient) { books, ui ->
        val sorted = when (ui.sort) {
            SortMode.RECENT -> books
            SortMode.TITLE -> books.sortedBy { it.title.lowercase() }
        }
        ui.copy(loading = false, books = sorted)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), transient.value)

    fun setSort(sort: SortMode) {
        savedState[KEY_SORT] = sort
        transient.update { it.copy(sort = sort) }
    }

    fun import(file: PickedFile) {
        viewModelScope.launch {
            transient.update { it.copy(importing = true) }
            try {
                importer.import(file)
            } catch (e: ImportException) {
                transient.update { it.copy(error = e.message) }
            } finally {
                transient.update { it.copy(importing = false) }
            }
        }
    }

    fun setDocumentType(bookId: String, type: DocumentType) {
        viewModelScope.launch { repository.setDocumentType(bookId, type) }
    }

    fun dismissError() = transient.update { it.copy(error = null) }

    fun delete(bookId: String) {
        scheduler.cancel(bookId)
        viewModelScope.launch { repository.deleteBook(bookId) }
    }

    private companion object {
        const val KEY_SORT = "sort"
    }
}
