package dev.joseramos.aireader.feature.reader.summary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dev.joseramos.aireader.ai.llm.KeyPointsGenerator
import dev.joseramos.aireader.ai.llm.KeyPointsJob
import dev.joseramos.aireader.ai.llm.KeyPointsJobKey
import dev.joseramos.aireader.ai.llm.Recap
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.ChapterKeyPoints
import dev.joseramos.aireader.core.data.book.KeyPointsRepository
import dev.joseramos.aireader.core.data.settings.BudgetLevel
import dev.joseramos.aireader.core.data.settings.CostConfirmation
import dev.joseramos.aireader.core.data.settings.DailyUsage
import dev.joseramos.aireader.core.data.settings.SecretStore
import dev.joseramos.aireader.core.data.settings.UsageRepository
import dev.joseramos.aireader.feature.reader.ReaderRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Hechos clave de los capítulos del libro (por id de capítulo), los trabajos de IA en curso o fallidos
 * y el último repaso «Hasta ahora…».
 */
data class KeyPointsUiState(
    val keyPoints: Map<Long, ChapterKeyPoints> = emptyMap(),
    val jobs: Map<KeyPointsJobKey, KeyPointsJob> = emptyMap(),
    val recap: Recap? = null
)

class KeyPointsViewModel(
    savedStateHandle: SavedStateHandle,
    repository: KeyPointsRepository,
    private val generator: KeyPointsGenerator,
    private val secrets: SecretStore,
    private val usage: UsageRepository
) : ViewModel() {
    /** Consumo de IA de hoy, para el anillo de la hoja ✦. */
    val today: StateFlow<DailyUsage> =
        usage.today.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DailyUsage())

    /** Sin clave, la hoja ✦ muestra las acciones bloqueadas. Empieza en `true` para no mostrar el aviso de golpe. */
    val hasApiKey: StateFlow<Boolean> =
        secrets.hasApiKey.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val bookId = savedStateHandle.toRoute<ReaderRoute>().bookId

    val state: StateFlow<KeyPointsUiState> = combine(
        repository.observe(bookId),
        generator.jobs,
        generator.recaps
    ) { keyPoints, jobs, recaps ->
        KeyPointsUiState(keyPoints, jobs.filterKeys { it.bookId == bookId }, recaps[bookId])
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KeyPointsUiState())

    fun jobFor(state: KeyPointsUiState, chapter: Chapter): KeyPointsJob? =
        state.jobs[KeyPointsJobKey(bookId, chapter.id)]

    fun recapJob(state: KeyPointsUiState): KeyPointsJob? = state.jobs[KeyPointsJobKey(bookId, null)]

    /**
     * El repaso sirve mientras no cubra páginas por delante de [page] y no se haya avanzado más de
     * un capítulo desde que se hizo; si no, hay que generar otro.
     */
    fun recapIsCurrent(recap: Recap?, chapters: List<Chapter>, page: Int): Boolean {
        val until = recap?.untilPage ?: return false
        if (until > page) return false
        val chapterOf = { p: Int -> chapters.indexOfLast { p >= it.startPage } }
        return chapterOf(page) - chapterOf(until) <= 1
    }

    private val _costConfirmation = MutableStateFlow<Pair<Int, CostConfirmation>?>(null)

    /** Repaso (página y estimación) que no cabe en lo que queda del presupuesto de hoy: pendiente de confirmar. */
    val costConfirmation: StateFlow<Pair<Int, CostConfirmation>?> = _costConfirmation

    /**
     * Genera el repaso hasta [page] (con los hechos clave que falten) o, si no cabe en lo que queda del
     * presupuesto de hoy, pide confirmación.
     */
    fun requestRecap(page: Int) {
        viewModelScope.launch {
            val confirmation = usage.today.first().confirmationFor(generator.estimateRecap(bookId, page).total)
            if (confirmation == null) generator.recap(bookId, page) else _costConfirmation.value = page to confirmation
        }
    }

    fun confirmRecap() {
        _costConfirmation.value?.let { (page, _) -> generator.recap(bookId, page) }
        _costConfirmation.value = null
    }

    fun cancelRecap() {
        _costConfirmation.value = null
    }

    fun dismissBudgetAlert(level: BudgetLevel) {
        viewModelScope.launch { usage.dismissAlert(level) }
    }

    /** Guarda la clave introducida desde el lector y, después, reintenta con [then]. */
    fun saveApiKey(key: String, then: () -> Unit) {
        viewModelScope.launch {
            secrets.setApiKey(key)
            then()
        }
    }

    /** Genera los hechos clave de [chapter] si faltan, o de nuevo con [replace]. */
    fun generate(chapter: Chapter, replace: Boolean = false) = generator.generate(bookId, chapter, replace)
}
