package dev.joseramos.aireader.feature.reader.summary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.joseramos.aireader.ai.llm.SummaryGenerator
import dev.joseramos.aireader.ai.llm.SummaryJob
import dev.joseramos.aireader.ai.llm.SummaryKey
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.Summary
import dev.joseramos.aireader.core.data.book.SummaryRepository
import dev.joseramos.aireader.core.data.db.SummaryKind
import dev.joseramos.aireader.core.data.settings.DailyUsage
import dev.joseramos.aireader.core.data.settings.SecretStore
import dev.joseramos.aireader.core.data.settings.UsageRepository
import dev.joseramos.aireader.feature.reader.ReaderRoute
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Resumen que se está mostrando: de un capítulo (en breve o detallado) o del libro entero. */
data class SummaryTarget(val chapter: Chapter?, val detailed: Boolean = false) {
    val kind: SummaryKind get() = when {
        chapter == null -> SummaryKind.BOOK
        detailed -> SummaryKind.CHAPTER_LONG
        else -> SummaryKind.CHAPTER_SHORT
    }
}

data class SummariesUiState(
    val summaries: List<Summary> = emptyList(),
    val jobs: Map<SummaryKey, SummaryJob> = emptyMap()
)

@HiltViewModel
class SummaryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: SummaryRepository,
    private val generator: SummaryGenerator,
    private val secrets: SecretStore,
    usage: UsageRepository
) : ViewModel() {
    /** Consumo de IA de hoy, para el anillo de la hoja ✦. */
    val today: StateFlow<DailyUsage> =
        usage.today.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DailyUsage())

    private val bookId = savedStateHandle.toRoute<ReaderRoute>().bookId

    val state: StateFlow<SummariesUiState> = combine(repository.observe(bookId), generator.jobs) { summaries, jobs ->
        SummariesUiState(summaries, jobs.filterKeys { it.bookId == bookId })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SummariesUiState())

    fun summaryFor(state: SummariesUiState, target: SummaryTarget): Summary? =
        state.summaries.firstOrNull { it.chapterId == target.chapter?.id && it.kind == target.kind }

    fun jobFor(state: SummariesUiState, target: SummaryTarget): SummaryJob? =
        state.jobs[SummaryKey(bookId, target.chapter?.id, target.kind)]

    /** Repaso «Hasta ahora…» guardado, si lo hay. */
    fun recapFor(state: SummariesUiState): Summary? = state.summaries.firstOrNull { it.kind == SummaryKind.RECAP }

    fun recapJob(state: SummariesUiState): SummaryJob? = state.jobs[SummaryKey(bookId, null, SummaryKind.RECAP)]

    /**
     * El repaso sirve mientras no cubra páginas por delante de [page] y no se haya avanzado más de
     * un capítulo desde que se hizo; si no, hay que generar otro.
     */
    fun recapIsCurrent(recap: Summary?, chapters: List<Chapter>, page: Int): Boolean {
        val until = recap?.untilPage ?: return false
        if (until > page) return false
        val chapterOf = { p: Int -> chapters.indexOfLast { p >= it.startPage } }
        return chapterOf(page) - chapterOf(until) <= 1
    }

    fun generateRecap(page: Int) = generator.recap(bookId, page)

    /** Guarda la clave introducida desde el lector y, después, reintenta con [then]. */
    fun saveApiKey(key: String, then: () -> Unit) {
        viewModelScope.launch {
            secrets.setApiKey(key)
            then()
        }
    }

    fun generate(target: SummaryTarget) {
        val chapter = target.chapter
        if (chapter ==
            null
        ) {
            generator.summarizeBook(bookId)
        } else {
            generator.summarizeChapter(bookId, chapter, target.detailed)
        }
    }
}
