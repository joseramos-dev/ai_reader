package dev.joseramos.aireader.indexing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.joseramos.aireader.core.data.db.BookDao

/**
 * Indexa un libro en segundo plano con WorkManager, en primer plano (tipo `dataSync`) para que Android no lo
 * detenga. Si Android lo detiene, WorkManager lo relanza y [IndexRunner] continúa donde se quedó.
 */
class IndexWorker(
    context: Context,
    params: WorkerParameters,
    private val runner: IndexRunner,
    private val bookDao: BookDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        return when (runner.index(bookId, runAttemptCount) { title -> setForeground(foregroundInfo(title)) }) {
            IndexResult.SUCCESS -> Result.success()
            IndexResult.RETRY -> Result.retry()
            IndexResult.FAILED -> Result.failure()
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
        private const val CHANNEL_ID = "indexing"
        private const val NOTIFICATION_ID = 4101
    }
}
