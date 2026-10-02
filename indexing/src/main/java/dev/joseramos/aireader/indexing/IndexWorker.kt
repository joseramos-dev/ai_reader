package dev.joseramos.aireader.indexing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.joseramos.aireader.core.data.db.BookDao
import dev.joseramos.aireader.core.data.db.IndexStatus
import java.util.Optional
import kotlinx.coroutines.CancellationException

/**
 * Etapa opcional de embeddings (F7). La aporta el módulo de RAG; mientras no exista, la
 * indexación termina tras el texto y los capítulos.
 */
interface EmbeddingStage {
    /** `false` si no se puede ejecutar todavía (por ejemplo, falta descargar el modelo). */
    suspend fun run(bookId: String, onProgress: suspend (Float) -> Unit): Boolean
}

/**
 * Arranca el análisis de personajes (F10) cuando un libro termina de indexarse. Lo aporta
 * `:ai:characters`, que decide si toca (novela, análisis automático y clave de API).
 */
interface CharacterAnalysisTrigger {
    suspend fun onBookIndexed(bookId: String)
}

/**
 * Indexa un libro en segundo plano: texto → capítulos → tipo de documento → embeddings. Cada etapa guarda su
 * progreso en Room, así que si Android detiene el trabajo, WorkManager lo relanza y continúa.
 */
@HiltWorker
class IndexWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val bookDao: BookDao,
    private val textIndexer: TextIndexer,
    private val chapterDetector: ChapterDetector,
    private val documentTypeDetector: DocumentTypeDetector,
    private val embeddingStage: Optional<EmbeddingStage>,
    private val characterAnalysis: Optional<CharacterAnalysisTrigger>
) : CoroutineWorker(context, params) {

    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val book = bookDao.get(bookId) ?: return Result.success()
        runCatching { setForeground(foregroundInfo(book.title)) }

        return try {
            bookDao.updateIndexState(bookId, IndexStatus.EXTRACTING_TEXT, 0f)
            textIndexer.run(book) { bookDao.updateIndexState(bookId, IndexStatus.EXTRACTING_TEXT, it * TEXT_SHARE) }
            chapterDetector.run(book)
            documentTypeDetector.run(book)

            val embeddings = embeddingStage.orElse(null)
            val embedded = embeddings?.run(bookId) {
                bookDao.updateIndexState(bookId, IndexStatus.EMBEDDING, TEXT_SHARE + it * (1 - TEXT_SHARE))
            } ?: true
            // Si falta el modelo de embeddings, el libro ya se puede leer y escuchar; el chat lo pedirá.
            bookDao.updateIndexState(
                bookId,
                if (embedded) IndexStatus.READY else IndexStatus.TEXT_READY,
                if (embedded) 1f else TEXT_SHARE
            )
            characterAnalysis.orElse(null)?.let { runCatching { it.onBookIndexed(bookId) } }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Fallo indexando $bookId (intento ${runAttemptCount + 1})", e)
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                bookDao.updateIndexState(bookId, IndexStatus.FAILED, 0f)
                Result.failure()
            }
        }
    }

    private fun foregroundInfo(title: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.indexing_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.indexing_title))
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
        private const val TAG = "IndexWorker"
        private const val CHANNEL_ID = "indexing"
        private const val NOTIFICATION_ID = 4101
        private const val MAX_ATTEMPTS = 3

        /** Parte de la barra de progreso que corresponde al texto y los capítulos. */
        private const val TEXT_SHARE = 0.5f
    }
}
