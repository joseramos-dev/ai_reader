package dev.joseramos.aireader.feature.characters

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.joseramos.aireader.ai.characters.AnalysisProgress
import dev.joseramos.aireader.ai.characters.CharacterAnalysis
import dev.joseramos.aireader.ai.characters.CharacterBrowser
import dev.joseramos.aireader.ai.characters.CharactersSnapshot
import dev.joseramos.aireader.ai.characters.VisibleCharacter
import dev.joseramos.aireader.core.data.book.Book
import dev.joseramos.aireader.core.data.book.BookRepository
import dev.joseramos.aireader.core.data.settings.SecretStore
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class CharacterSort { APPEARANCE, IMPORTANCE }

data class CharactersUiState(
    val loading: Boolean = true,
    val book: Book? = null,
    val snapshot: CharactersSnapshot = CharactersSnapshot(),
    val progress: AnalysisProgress = AnalysisProgress(),
    val hasApiKey: Boolean = true,
    val sort: CharacterSort = CharacterSort.APPEARANCE
) {
    val characters: List<VisibleCharacter>
        get() = when (sort) {
            CharacterSort.APPEARANCE -> snapshot.characters
            CharacterSort.IMPORTANCE -> snapshot.characters.sortedByDescending { it.mentions }
        }
}

@HiltViewModel
class CharactersViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    books: BookRepository,
    browser: CharacterBrowser,
    private val secrets: SecretStore,
    private val analysis: CharacterAnalysis
) : ViewModel() {
    private val bookId = savedStateHandle.toRoute<CharactersRoute>().bookId
    private val sort = MutableStateFlow(CharacterSort.APPEARANCE)

    val state: StateFlow<CharactersUiState> = combine(
        books.observeBook(bookId),
        browser.observe(bookId),
        analysis.observe(bookId),
        secrets.hasApiKey,
        sort
    ) { book, snapshot, progress, hasKey, sortMode ->
        CharactersUiState(false, book, snapshot, progress, hasKey, sortMode)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CharactersUiState())

    fun setSort(mode: CharacterSort) {
        sort.value = mode
    }

    fun analyze() = analysis.start(bookId)

    /** Guarda la clave introducida desde esta pantalla y arranca el análisis. */
    fun saveApiKeyAndAnalyze(key: String) {
        viewModelScope.launch {
            secrets.setApiKey(key)
            analysis.start(bookId)
        }
    }
}
