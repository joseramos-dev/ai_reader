package dev.joseramos.aireader.indexing

import android.util.Log
import dev.joseramos.aireader.core.common.ApplicationScope
import dev.joseramos.aireader.core.data.db.BookDao
import dev.joseramos.aireader.core.data.db.IndexStatus
import dev.joseramos.aireader.core.data.settings.SecretStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Cuando hay clave de API (al arrancar la app o al introducirla), vuelve a encolar los libros que
 * se indexaron sin ella ([dev.joseramos.aireader.core.data.db.BookEntity.aiPrepared]), para que
 * [IndexWorker] repase las etapas que usan la IA. Los que aún están con el texto siguen su trabajo
 * (verán la clave al llegar a esas etapas); el resto se relanza, incluido uno que se hubiera quedado
 * colgado a medias, y continúa desde donde estaba.
 */
@Singleton
class ApiKeyObserver @Inject constructor(
    private val secrets: SecretStore,
    private val bookDao: BookDao,
    private val scheduler: IndexScheduler,
    @ApplicationScope private val scope: CoroutineScope
) {
    fun start() {
        scope.launch {
            secrets.hasApiKey.distinctUntilChanged().filter { it }.collect { prepareWithAi() }
        }
    }

    private suspend fun prepareWithAi() {
        val books = runCatching { bookDao.notAiPrepared() }.getOrDefault(emptyList())
        if (books.isNotEmpty()) Log.i(TAG, "Repasando con IA ${books.size} libros indexados sin clave")
        books.forEach { scheduler.enqueue(it.id, replace = it.indexStatus !in EXTRACTING) }
    }

    private companion object {
        const val TAG = "ApiKeyObserver"
        val EXTRACTING = setOf(IndexStatus.PENDING, IndexStatus.EXTRACTING_TEXT)
    }
}
