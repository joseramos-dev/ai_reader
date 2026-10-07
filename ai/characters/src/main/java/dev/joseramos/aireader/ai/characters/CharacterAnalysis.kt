package dev.joseramos.aireader.ai.characters

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.joseramos.aireader.ai.llm.LlmClient
import dev.joseramos.aireader.core.common.Log
import dev.joseramos.aireader.core.data.book.BookContentRepository
import dev.joseramos.aireader.core.data.book.BookRepository
import dev.joseramos.aireader.core.data.book.CharacterRepository
import dev.joseramos.aireader.core.data.db.IndexStatus
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import dev.joseramos.aireader.core.data.settings.UsageRepository
import dev.joseramos.aireader.indexing.CharacterAnalysisTrigger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** Cómo va el análisis de personajes de un libro: capítulos analizados de [total]. */
data class AnalysisProgress(
    val scanned: Int = 0,
    val total: Int = 0,
    val running: Boolean = false,
    val failed: Boolean = false,
    /** El último intento falló porque la clave de API de Gemini falta o no es válida. */
    val needsApiKey: Boolean = false
) {
    val complete: Boolean get() = total > 0 && scanned >= total
}

/**
 * Arranca y sigue el análisis de personajes. Solo se hace con novelas y con clave de API; con el ajuste
 * automático se lanza al terminar la indexación (o al abrir el libro, si entonces no se pudo), y si no,
 * cuando el usuario lo pide desde el menú de personajes.
 */
class CharacterAnalysis(
    private val context: Context,
    private val books: BookRepository,
    private val content: BookContentRepository,
    private val characters: CharacterRepository,
    private val settings: SettingsRepository,
    private val llm: LlmClient,
    private val extractor: CharacterExtractor,
    private val usage: UsageRepository
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Lanza el análisis (o lo deja como está si ya está en marcha). */
    fun start(bookId: String) {
        val request = OneTimeWorkRequestBuilder<CharacterScanWorker>()
            .setInputData(workDataOf(CharacterScanWorker.KEY_BOOK_ID to bookId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(workName(bookId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(bookId: String) {
        workManager.cancelUniqueWork(workName(bookId))
    }

    /** Con el análisis automático activado, lo arranca si hace falta. */
    suspend fun startIfAuto(bookId: String) {
        if (settings.settings.first().autoCharacterAnalysis) startIfNeeded(bookId)
    }

    /**
     * Arranca el análisis si es una novela ya indexada, hay clave y quedan capítulos por analizar.
     * Si no cabe en lo que queda del presupuesto de hoy, no arranca solo: queda el botón de la
     * pantalla de personajes, que pide confirmación.
     */
    suspend fun startIfNeeded(bookId: String) {
        val book = books.getBook(bookId) ?: return
        val indexed = book.indexStatus in setOf(IndexStatus.READY, IndexStatus.TEXT_READY, IndexStatus.EMBEDDING)
        if (!book.isLiterature || !indexed || !llm.hasApiKey()) return
        val progress = observe(bookId).first()
        if (progress.running || progress.complete) return
        val confirmation = usage.today.first().confirmationFor(estimateTokens(bookId))
        if (confirmation != null) {
            Log.i(
                TAG,
                "Análisis de $bookId sin arrancar: ~${confirmation.estimatedTokens} tokens no caben en el presupuesto"
            )
            return
        }
        start(bookId)
    }

    /** Tokens que costaría analizar los capítulos que faltan (estimación local). */
    suspend fun estimateTokens(bookId: String): Long = extractor.estimate(bookId).total

    fun observe(bookId: String): Flow<AnalysisProgress> = combine(
        characters.observe(bookId),
        content.observeChapters(bookId),
        workManager.getWorkInfosForUniqueWorkFlow(workName(bookId))
    ) { data, chapters, work ->
        val lastWork = work.lastOrNull()
        val state = lastWork?.state
        AnalysisProgress(
            scanned = chapters.count { it.id in data.scannedChapterIds },
            total = chapters.size,
            running = state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED,
            failed = state == WorkInfo.State.FAILED,
            needsApiKey = state == WorkInfo.State.FAILED &&
                lastWork.outputData.getString(CharacterScanWorker.KEY_REASON) == CharacterScanWorker.REASON_API_KEY
        )
    }

    private fun workName(bookId: String) = "characters-$bookId"

    private companion object {
        const val TAG = "CharacterAnalysis"
        const val RETRY_DELAY_MINUTES = 1L
    }
}

/** Puente con la indexación, para que los módulos de pantalla no dependan de `:indexing`. */
class IndexedBookTrigger(private val analysis: CharacterAnalysis) : CharacterAnalysisTrigger {
    override suspend fun onBookIndexed(bookId: String) = analysis.startIfAuto(bookId)
}
