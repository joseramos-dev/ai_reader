package dev.joseramos.aireader.indexing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.joseramos.aireader.core.data.db.BookDao
import dev.joseramos.aireader.core.data.db.BookEntity
import dev.joseramos.aireader.core.data.db.IndexStatus
import dev.joseramos.aireader.core.data.settings.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Etapa opcional del chat (F7): fragmentos y embeddings. La aporta el módulo de RAG; mientras no
 * exista, la indexación termina tras el texto y los capítulos.
 */
interface EmbeddingStage {
    /**
     * Trocea el libro en fragmentos (con su índice de texto completo) si aún no los tiene. No usa el
     * modelo de embeddings: así el chat puede buscar por palabras aunque el modelo falte o falle.
     */
    suspend fun prepareChunks(bookId: String)

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
 * progreso en Room, así que si Android detiene el trabajo, WorkManager lo relanza y continúa. Si el
 * libro se indexó sin clave de API y ahora la hay, repasa las etapas que usan la IA
 * ([BookEntity.aiPrepared]).
 */
class IndexWorker(
    context: Context,
    params: WorkerParameters,
    private val bookDao: BookDao,
    private val textIndexer: TextIndexer,
    private val chapterDetector: ChapterDetector,
    private val documentTypeDetector: DocumentTypeDetector,
    private val embeddingStage: EmbeddingStage?,
    private val characterAnalysis: CharacterAnalysisTrigger?,
    private val crashGuard: StageCrashGuard,
    private val secrets: SecretStore
) : CoroutineWorker(context, params) {

    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val book = bookDao.get(bookId) ?: return Result.success()
        // Si Android no deja pasar a primer plano (por ejemplo, desde segundo plano en Android 12+),
        // el trabajo sigue igual; como es reanudable, si lo detienen continuará donde se quedó.
        runCatching { setForeground(foregroundInfo(book.title)) }
            .onFailure { Log.w(TAG, "Sin primer plano para $bookId", it) }

        return try {
            val upgraded = text(book)
            // Un libro ya listo que solo se repasa (por ejemplo, al introducir la clave) no vuelve a «preparando».
            if (!upgraded && book.indexStatus != IndexStatus.READY) {
                bookDao.updateIndexState(bookId, IndexStatus.EMBEDDING, TEXT_SHARE)
            }
            // La clave se mira tras el texto, que es lo largo: así cuenta aunque se haya introducido mientras.
            val withAi = secrets.hasApiKey.first()
            val retryWithAi = withAi && !book.aiPrepared
            chapterDetector.run(book, upgrade = upgraded, retryWithAi = retryWithAi)
            documentTypeDetector.run(book, retryWithAi = retryWithAi)

            val embedded = searchIndex(bookId)
            // Si los embeddings faltan o fallan, el libro ya se puede leer y escuchar, y el chat busca por palabras.
            bookDao.updateIndexState(
                bookId,
                if (embedded) IndexStatus.READY else IndexStatus.TEXT_READY,
                if (embedded) 1f else TEXT_SHARE
            )
            if (withAi) bookDao.setAiPrepared(bookId, true)
            characterAnalysis?.let { runCatching { it.onBookIndexed(bookId) } }
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

    /**
     * Etapa de texto. Si el trabajo se relanza (Android lo detuvo o la app se cerró) y el texto ya
     * estaba, no se vuelve a «extrayendo texto 0 %». Si el texto es de una versión anterior del
     * limpiador, se rehace sin cambiar el estado, porque el libro se sigue pudiendo leer con el texto
     * antiguo mientras tanto; en ese caso devuelve `true`.
     */
    private suspend fun text(book: BookEntity): Boolean {
        if (textIndexer.isComplete(book)) return false
        val upgrade = textIndexer.hasOutdatedText(book)
        Log.i(TAG, "${if (upgrade) "Actualizando" else "Extrayendo"} el texto de ${book.id} (${book.pageCount} págs.)")
        if (upgrade) {
            textIndexer.run(book) {}
        } else {
            bookDao.updateIndexState(book.id, IndexStatus.EXTRACTING_TEXT, 0f)
            textIndexer.run(book) { bookDao.updateIndexState(book.id, IndexStatus.EXTRACTING_TEXT, it * TEXT_SHARE) }
        }
        return upgrade
    }

    /**
     * Fragmentos y vectores para el chat. Los vectores son la etapa más pesada (un modelo ONNX en el
     * móvil). Cada etapa va protegida por [StageCrashGuard]: si falla o tumba la app dos veces seguidas
     * con este libro, se salta y el libro queda listo para leer, en lugar de reintentarlo para siempre.
     */
    private suspend fun searchIndex(bookId: String): Boolean {
        val stage = embeddingStage ?: return true
        val chunked = guarded(StageCrashGuard.CHUNKS, bookId) {
            stage.prepareChunks(bookId)
            true
        }
        return chunked &&
            guarded(StageCrashGuard.EMBEDDINGS, bookId) {
                stage.run(bookId) {
                    bookDao.updateIndexState(bookId, IndexStatus.EMBEDDING, TEXT_SHARE + it * (1 - TEXT_SHARE))
                }
            }
    }

    @Suppress("TooGenericExceptionCaught") // Incluye errores nativos y de memoria del modelo.
    private suspend fun guarded(name: String, bookId: String, block: suspend () -> Boolean): Boolean {
        if (crashGuard.shouldSkip(name, bookId)) {
            Log.w(TAG, "Se omite la etapa $name de $bookId: no terminó en los últimos intentos o no funciona aquí")
            return false
        }
        crashGuard.begin(name, bookId)
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkageError) {
            // Falta o no carga una biblioteca nativa: no se arreglará reintentando hasta actualizar la app.
            Log.e(TAG, "La etapa $name no puede funcionar en este dispositivo", e)
            crashGuard.markUnsupported(name)
            false
        } catch (e: Throwable) {
            Log.e(TAG, "Fallo en la etapa $name de $bookId", e)
            false
        } finally {
            crashGuard.end(name, bookId)
        }
    }

    /** Necesario para los trabajos urgentes en Android 11 o anterior. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(inputData.getString(KEY_BOOK_ID)?.let { bookDao.get(it)?.title }.orEmpty())

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
