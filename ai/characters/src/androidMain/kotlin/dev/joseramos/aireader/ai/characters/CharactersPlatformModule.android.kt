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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.androidx.workmanager.dsl.worker
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/** Planifica el análisis con WorkManager: con red, y con espera creciente entre reintentos. */
internal class WorkManagerCharacterScanScheduler(private val context: Context) : CharacterScanScheduler {
    private val workManager get() = WorkManager.getInstance(context)

    override fun start(bookId: String) {
        val request = OneTimeWorkRequestBuilder<CharacterScanWorker>()
            .setInputData(workDataOf(CharacterScanWorker.KEY_BOOK_ID to bookId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(workName(bookId), ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(bookId: String) {
        workManager.cancelUniqueWork(workName(bookId))
    }

    override fun observe(bookId: String): Flow<ScanJobState> =
        workManager.getWorkInfosForUniqueWorkFlow(workName(bookId)).map { work ->
            val last = work.lastOrNull()
            val state = last?.state
            ScanJobState(
                active = state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED,
                failed = state == WorkInfo.State.FAILED,
                needsApiKey = state == WorkInfo.State.FAILED &&
                    last.outputData.getString(CharacterScanWorker.KEY_REASON) == CharacterScanWorker.REASON_API_KEY
            )
        }

    private fun workName(bookId: String) = "characters-$bookId"

    private companion object {
        const val RETRY_DELAY_MINUTES = 1L
    }
}

actual val charactersPlatformModule: Module = module {
    single { WorkManagerCharacterScanScheduler(get()) } bind CharacterScanScheduler::class
    worker { CharacterScanWorker(get(), get(), get()) }
}
