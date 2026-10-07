package dev.joseramos.aireader.feature.characters

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dev.joseramos.aireader.ai.characters.CharacterBrowser
import dev.joseramos.aireader.ai.characters.CharactersSnapshot
import dev.joseramos.aireader.ai.characters.VisibleCharacter
import dev.joseramos.aireader.ai.characters.VisibleRelation
import dev.joseramos.aireader.core.data.book.BookContentRepository
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.ReadingPositionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class RelationsGraphUiState(
    val loading: Boolean = true,
    val snapshot: CharactersSnapshot = CharactersSnapshot(),
    val nodes: List<VisibleCharacter> = emptyList(),
    /** Una arista por pareja: la relación vigente más reciente. */
    val edges: List<VisibleRelation> = emptyList(),
    val positions: Map<Long, Point> = emptyMap(),
    /** Capítulos ya empezados: el filtro nunca llega más allá de lo leído. */
    val chapters: List<Chapter> = emptyList(),
    /** Índice en [chapters] del filtro «hasta el capítulo N», o `null` para todo lo leído. */
    val chapterLimit: Int? = null,
    val hideMinor: Boolean = false
)

private data class GraphFilters(val chapterLimit: Int?, val hideMinor: Boolean)

class RelationsGraphViewModel(
    savedStateHandle: SavedStateHandle,
    browser: CharacterBrowser,
    content: BookContentRepository,
    positions: ReadingPositionRepository
) : ViewModel() {
    val bookId = savedStateHandle.toRoute<RelationsGraphRoute>().bookId
    private val filters = MutableStateFlow(GraphFilters(chapterLimit = null, hideMinor = false))

    /** Últimas posiciones calculadas, para que el grafo no se recoloque entero al cambiar un filtro. */
    private var lastPositions = emptyMap<Long, Point>()

    private val readChapters = combine(content.observeChapters(bookId), positions.observeMaxPage(bookId)) { all, max ->
        all.filter { it.startPage <= max }
    }

    private val pageLimit = combine(readChapters, filters) { chapters, f ->
        f.chapterLimit?.let { chapters.getOrNull(it)?.endPage }
    }

    val state: StateFlow<RelationsGraphUiState> = combine(
        browser.observe(bookId, pageLimit),
        readChapters,
        filters
    ) { snapshot, chapters, f -> build(snapshot, chapters, f) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RelationsGraphUiState())

    fun setChapterLimit(index: Int?) {
        filters.value = filters.value.copy(chapterLimit = index)
    }

    fun setHideMinor(hide: Boolean) {
        filters.value = filters.value.copy(hideMinor = hide)
    }

    private fun build(snapshot: CharactersSnapshot, chapters: List<Chapter>, f: GraphFilters): RelationsGraphUiState {
        val current = snapshot.relations
            .filter { it.isCurrent }
            .groupBy { minOf(it.fromId, it.toId) to maxOf(it.fromId, it.toId) }
            .map { (_, list) -> list.maxBy { it.page } }
        val threshold =
            maxOf(MIN_MENTIONS, ((snapshot.characters.maxOfOrNull { it.mentions } ?: 0) * MINOR_SHARE).toInt())
        val nodes = if (f.hideMinor) {
            val linked = current.flatMapTo(mutableSetOf()) { listOf(it.fromId, it.toId) }
            snapshot.characters.filter { it.mentions >= threshold && it.id in linked }.ifEmpty { snapshot.characters }
        } else {
            snapshot.characters
        }
        val ids = nodes.mapTo(mutableSetOf()) { it.id }
        val edges = current.filter { it.fromId in ids && it.toId in ids }
        val layout = ForceLayout.layout(nodes.map { it.id }, edges.map { it.fromId to it.toId }, lastPositions)
        lastPositions = lastPositions + layout
        return RelationsGraphUiState(
            loading = false,
            snapshot = snapshot,
            nodes = nodes,
            edges = edges,
            positions = layout,
            chapters = chapters,
            chapterLimit = f.chapterLimit,
            hideMinor = f.hideMinor
        )
    }

    private companion object {
        const val MIN_MENTIONS = 3
        const val MINOR_SHARE = 0.05
    }
}
