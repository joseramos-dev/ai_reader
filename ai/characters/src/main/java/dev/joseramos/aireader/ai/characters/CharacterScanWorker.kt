package dev.joseramos.aireader.ai.characters

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.joseramos.aireader.ai.llm.LlmClient
import dev.joseramos.aireader.ai.llm.LlmException
import dev.joseramos.aireader.core.common.Log
import dev.joseramos.aireader.core.data.book.BookContentRepository
import dev.joseramos.aireader.core.data.book.BookRepository
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.db.CharacterDao
import dev.joseramos.aireader.core.data.db.CharacterScanEntity
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Recorre los capítulos de una novela en orden y extrae sus personajes. Cada capítulo terminado se
 * apunta en `character_scans`, así que si el trabajo se interrumpe continúa por donde iba.
 */
class CharacterScanWorker(
    context: Context,
    params: WorkerParameters,
    private val books: BookRepository,
    private val content: BookContentRepository,
    private val dao: CharacterDao,
    private val extractor: CharacterExtractor,
    private val llm: LlmClient,
    private val settings: SettingsRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val book = books.getBook(bookId) ?: return Result.success()
        if (!llm.hasApiKey()) return apiKeyFailure()
        runCatching { setForeground(foregroundInfo(book.title)) }

        val model = settings.settings.first().analysisModel
        val chapters = content.chapters(bookId)
        val done = dao.scannedChapterIds(bookId).toSet()
        for (chapter in chapters) {
            try {
                if (chapter.id !in done) scan(bookId, book.title, chapter, model)
            } catch (e: LlmException.RateLimited) {
                // El nivel gratuito de Gemini limita las peticiones por minuto y por día: se espera y se
                // sigue por el mismo capítulo (WorkManager reintenta con espera creciente).
                Log.w(TAG, "Límite de peticiones en el capítulo ${chapter.number}", e)
                return if (runAttemptCount >= MAX_RATE_LIMIT_ATTEMPTS) Result.failure() else Result.retry()
            } catch (e: LlmException) {
                Log.w(TAG, "Fallo analizando el capítulo ${chapter.number} (intento ${runAttemptCount + 1})", e)
                if (e is LlmException.NoApiKey || e is LlmException.Unauthorized) return apiKeyFailure()
                return if (e.isPermanent() || runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
            }
        }
        return Result.success()
    }

    private suspend fun scan(bookId: String, title: String, chapter: Chapter, model: String) {
        try {
            extractor.scan(bookId, title, chapter, model)
        } catch (e: LlmException.Refused) {
            // Un capítulo que el modelo no quiere analizar no debe bloquear el resto.
            Log.w(TAG, "Capítulo ${chapter.number} rechazado", e)
            dao.upsertScan(CharacterScanEntity(bookId, chapter.id, model, System.currentTimeMillis()))
        }
    }

    /** Falla por un problema con la clave de API, dejando el motivo para que la pantalla lo explique. */
    private fun apiKeyFailure() = Result.failure(workDataOf(KEY_REASON to REASON_API_KEY))

    private fun foregroundInfo(title: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.characters_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.characters_notification_title))
            .setContentText(title)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_BOOK_ID = "book_id"
        const val KEY_REASON = "reason"
        const val REASON_API_KEY = "api_key"
        private const val TAG = "CharacterScanWorker"
        private const val CHANNEL_ID = "characters"
        private const val NOTIFICATION_ID = 4102
        private const val MAX_ATTEMPTS = 5
        private const val MAX_RATE_LIMIT_ATTEMPTS = 20
    }
}
