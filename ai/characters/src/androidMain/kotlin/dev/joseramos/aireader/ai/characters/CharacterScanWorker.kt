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

/**
 * Analiza los personajes de un libro en segundo plano con WorkManager, en primer plano (tipo `dataSync`) para que
 * Android no lo detenga. Si Android lo detiene, WorkManager lo relanza y [CharacterScanRunner] continúa por donde iba.
 */
class CharacterScanWorker(context: Context, params: WorkerParameters, private val runner: CharacterScanRunner) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        return when (runner.scan(bookId, runAttemptCount) { title -> setForeground(foregroundInfo(title)) }) {
            ScanResult.SUCCESS -> Result.success()
            ScanResult.RETRY -> Result.retry()
            ScanResult.FAILED -> Result.failure()
            // Deja el motivo para que la pantalla lo explique.
            ScanResult.NEEDS_API_KEY -> Result.failure(workDataOf(KEY_REASON to REASON_API_KEY))
        }
    }

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
        private const val CHANNEL_ID = "characters"
        private const val NOTIFICATION_ID = 4102
    }
}
