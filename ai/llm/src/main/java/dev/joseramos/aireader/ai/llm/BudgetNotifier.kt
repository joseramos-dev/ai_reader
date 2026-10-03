package dev.joseramos.aireader.ai.llm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.joseramos.aireader.core.data.settings.BudgetLevel
import dev.joseramos.aireader.core.data.settings.DailyUsage
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import javax.inject.Inject

/**
 * Notificación del sistema cuando el consumo del día cruza el 80 % o el 100 % del presupuesto. Así
 * se entera también quien tiene un análisis en segundo plano. Solo avisa: la IA sigue funcionando.
 * Sin permiso de notificaciones no hace nada (en la app ya se ve el aviso).
 */
class BudgetNotifier @Inject constructor(@ApplicationContext private val context: Context) {

    fun onUsageChanged(before: DailyUsage, after: DailyUsage) {
        val level = after.level
        if (level <= before.level || level == BudgetLevel.OK || !canNotify()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.budget_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val numbers = NumberFormat.getIntegerInstance()
        val text = context.getString(
            R.string.budget_notification_text,
            numbers.format(after.tokens),
            numbers.format(after.budget),
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(after.resetsAt))
        )
        val title = context.getString(
            if (level == BudgetLevel.OVER) R.string.budget_notification_over else R.string.budget_notification_near
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun canNotify(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private companion object {
        const val CHANNEL_ID = "ai_budget"
        const val NOTIFICATION_ID = 4201
    }
}
