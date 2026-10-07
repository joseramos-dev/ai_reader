package dev.joseramos.aireader.ai.characters

import dev.joseramos.aireader.ai.llm.LlmClient
import dev.joseramos.aireader.ai.llm.LlmException
import dev.joseramos.aireader.core.common.Log
import dev.joseramos.aireader.core.common.currentTimeMillis
import dev.joseramos.aireader.core.data.book.BookContentRepository
import dev.joseramos.aireader.core.data.book.BookRepository
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.db.CharacterDao
import dev.joseramos.aireader.core.data.db.CharacterScanEntity
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Cómo va el trabajo de análisis de un libro: en marcha o en cola, o terminado con un fallo. */
data class ScanJobState(
    val active: Boolean = false,
    val failed: Boolean = false,
    /** El último intento falló porque la clave de API de Gemini falta o no es válida. */
    val needsApiKey: Boolean = false
)

/**
 * Programa el análisis de personajes de un libro como trabajo único: en Android lo conserva WorkManager aunque se
 * cierre la app; en Windows corre en la propia app. En ambos casos es [CharacterScanRunner] quien analiza.
 */
interface CharacterScanScheduler {
    /** Lanza el análisis (o lo deja como está si ya está en marcha). */
    fun start(bookId: String)

    fun cancel(bookId: String)

    fun observe(bookId: String): Flow<ScanJobState>
}

/** Qué hacer tras un intento de analizar un libro. */
enum class ScanResult {
    SUCCESS,
    RETRY,
    FAILED,

    /** Falla por un problema con la clave de API: la pantalla lo explica al usuario. */
    NEEDS_API_KEY
}

/**
 * Recorre los capítulos de una novela en orden y extrae sus personajes. Cada capítulo terminado se
 * apunta en `character_scans`, así que si el trabajo se interrumpe continúa por donde iba.
 */
class CharacterScanRunner(
    private val books: BookRepository,
    private val content: BookContentRepository,
    private val dao: CharacterDao,
    private val extractor: CharacterExtractor,
    private val llm: LlmClient,
    private val settings: SettingsRepository
) {
    /**
     * [attempt] es el intento actual (0 el primero). [onStart] se llama con el título del libro antes de empezar
     * (Android pasa a primer plano); si falla, el análisis sigue igual.
     */
    suspend fun scan(bookId: String, attempt: Int, onStart: suspend (title: String) -> Unit = {}): ScanResult {
        val book = books.getBook(bookId) ?: return ScanResult.SUCCESS
        if (!llm.hasApiKey()) return ScanResult.NEEDS_API_KEY
        runCatching { onStart(book.title) }

        val model = settings.settings.first().analysisModel
        val chapters = content.chapters(bookId)
        val done = dao.scannedChapterIds(bookId).toSet()
        for (chapter in chapters) {
            try {
                if (chapter.id !in done) scan(bookId, book.title, chapter, model)
            } catch (e: LlmException.RateLimited) {
                // El nivel gratuito de Gemini limita las peticiones por minuto y por día: se espera y se
                // sigue por el mismo capítulo (el planificador reintenta con espera creciente).
                Log.w(TAG, "Límite de peticiones en el capítulo ${chapter.number}", e)
                return if (attempt >= MAX_RATE_LIMIT_ATTEMPTS) ScanResult.FAILED else ScanResult.RETRY
            } catch (e: LlmException) {
                Log.w(TAG, "Fallo analizando el capítulo ${chapter.number} (intento ${attempt + 1})", e)
                if (e is LlmException.NoApiKey || e is LlmException.Unauthorized) return ScanResult.NEEDS_API_KEY
                return if (e.isPermanent() || attempt >= MAX_ATTEMPTS) ScanResult.FAILED else ScanResult.RETRY
            }
        }
        return ScanResult.SUCCESS
    }

    private suspend fun scan(bookId: String, title: String, chapter: Chapter, model: String) {
        try {
            extractor.scan(bookId, title, chapter, model)
        } catch (e: LlmException.Refused) {
            // Un capítulo que el modelo no quiere analizar no debe bloquear el resto.
            Log.w(TAG, "Capítulo ${chapter.number} rechazado", e)
            dao.upsertScan(CharacterScanEntity(bookId, chapter.id, model, currentTimeMillis()))
        }
    }

    private companion object {
        const val TAG = "CharacterScanWorker"
        const val MAX_ATTEMPTS = 5
        const val MAX_RATE_LIMIT_ATTEMPTS = 20
    }
}
