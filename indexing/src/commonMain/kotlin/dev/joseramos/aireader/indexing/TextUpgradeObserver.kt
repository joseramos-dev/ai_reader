package dev.joseramos.aireader.indexing

import dev.joseramos.aireader.core.common.Log
import dev.joseramos.aireader.core.data.db.PageTextDao
import dev.joseramos.aireader.text.TextCleaner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Al arrancar la app, vuelve a procesar los libros cuyo texto se limpió con una versión anterior de
 * [TextCleaner] (por ejemplo, para que tengan saltos de renglón, títulos y apartados). El libro se
 * puede seguir leyendo mientras tanto.
 */
class TextUpgradeObserver(
    private val pageTextDao: PageTextDao,
    private val scheduler: IndexScheduler,
    private val scope: CoroutineScope
) {
    fun start() {
        scope.launch {
            val outdated = runCatching {
                pageTextDao.bookIdsWithOutdatedText(TextCleaner.VERSION)
            }.getOrDefault(emptyList())
            if (outdated.isNotEmpty()) Log.i(TAG, "Actualizando el texto de ${outdated.size} libros")
            outdated.forEach { scheduler.enqueue(it) }
        }
    }

    private companion object {
        const val TAG = "TextUpgrade"
    }
}
