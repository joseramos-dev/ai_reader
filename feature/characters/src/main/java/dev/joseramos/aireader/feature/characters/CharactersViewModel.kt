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
import dev.joseramos.aireader.core.data.settings.CostConfirmation
import dev.joseramos.aireader.core.data.settings.SecretStore
import dev.joseramos.aireader.core.data.settings.UsageRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    private val analysis: CharacterAnalysis,
    private val usage: UsageRepository
) : ViewModel() {
    private val bookId = savedStateHandle.toRoute<CharactersRoute>().bookId
    private val sort = MutableStateFlow(CharacterSort.APPEARANCE)

    private val _costConfirmation = MutableStateFlow<CostConfirmation?>(null)

    /** Análisis que no cabe en lo que queda del presupuesto de hoy: pendiente de confirmar. */
    val costConfirmation: StateFlow<CostConfirmation?> = _costConfirmation

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

    fun analyze() {
        viewModelScope.launch { requestAnalysis() }
    }

    /** Guarda la clave introducida desde esta pantalla y arranca el análisis. */
    fun saveApiKeyAndAnalyze(key: String) {
        viewModelScope.launch {
            secrets.setApiKey(key)
            requestAnalysis()
        }
    }

    fun confirmAnalysis() {
        _costConfirmation.value = null
        analysis.start(bookId)
    }

    fun cancelAnalysis() {
        _costConfirmation.value = null
    }

    /** Arranca el análisis o, si no cabe en lo que queda del presupuesto de hoy, pide confirmación. */
    private suspend fun requestAnalysis() {
        val confirmation = usage.today.first().confirmationFor(analysis.estimateTokens(bookId))
        if (confirmation == null) analysis.start(bookId) else _costConfirmation.value = confirmation
    }
}
