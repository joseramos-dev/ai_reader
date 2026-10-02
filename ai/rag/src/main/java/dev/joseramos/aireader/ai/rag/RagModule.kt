package dev.joseramos.aireader.ai.rag

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.joseramos.aireader.ai.models.ModelCatalog
import dev.joseramos.aireader.ai.models.ModelManager
import dev.joseramos.aireader.ai.models.ModelState
import dev.joseramos.aireader.core.common.ApplicationScope
import dev.joseramos.aireader.core.data.db.BookDao
import dev.joseramos.aireader.core.data.db.IndexStatus
import dev.joseramos.aireader.indexing.EmbeddingStage
import dev.joseramos.aireader.indexing.IndexScheduler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Module
@InstallIn(SingletonComponent::class)
interface RagModule {
    /** Activa la etapa de embeddings de la indexación. */
    @Binds
    fun embeddingStage(impl: BookEmbeddingIndexer): EmbeddingStage
}

/**
 * Cuando se instala el modelo de embeddings, completa la indexación de los libros que se quedaron
 * a la espera (estado `TEXT_READY`). Se arranca una vez al iniciar la app.
 */
@Singleton
class EmbeddingModelObserver @Inject constructor(
    private val models: ModelManager,
    private val bookDao: BookDao,
    private val scheduler: IndexScheduler,
    @ApplicationScope private val scope: CoroutineScope
) {
    fun start() {
        scope.launch {
            models.states
                .map { it[ModelCatalog.e5Small.id] is ModelState.Installed }
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    bookDao.idsWithStatus(IndexStatus.TEXT_READY).forEach { id ->
                        scheduler.enqueue(id, replace = true)
                    }
                }
        }
    }
}
