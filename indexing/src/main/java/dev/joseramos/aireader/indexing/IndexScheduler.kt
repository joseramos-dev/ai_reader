package dev.joseramos.aireader.indexing

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encola la indexación de un libro como trabajo único: WorkManager la conserva aunque se cierre
 * la app y [IndexWorker] continúa desde donde se quedó.
 */
@Singleton
class IndexScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val workManager get() = WorkManager.getInstance(context)

    fun enqueue(bookId: String, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            .setInputData(workDataOf(IndexWorker.KEY_BOOK_ID to bookId))
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .addTag(TAG)
            .build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        workManager.enqueueUniqueWork(workName(bookId), policy, request)
    }

    fun cancel(bookId: String) {
        workManager.cancelUniqueWork(workName(bookId))
    }

    private fun workName(bookId: String) = "index-$bookId"

    companion object {
        const val TAG = "book-index"
    }
}
