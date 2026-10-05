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
import dev.joseramos.aireader.indexing.StageCrashGuard
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Module
@InstallIn(SingletonComponent::class)
interface RagModule {
    /** Activa la etapa de embeddings de la indexación. */
    @Binds
    fun embeddingStage(impl: BookEmbeddingIndexer): EmbeddingStage

    /** Decide con Gemini las ubicaciones dudosas y dónde ocurre un hecho. */
    @Binds
    fun locationJudge(impl: GeminiLocationJudge): LocationJudge
}

/**
 * Mantiene al día la parte del chat de la indexación. Se arranca una vez al iniciar la app:
 * - Vuelve a encolar los libros que se quedaron a medias (`PENDING`, `EXTRACTING_TEXT`, `EMBEDDING`)
 *   sin un trabajo pendiente, para que el chat no espere para siempre a una indexación que ya no corre.
 * - Con el modelo de embeddings instalado (al arrancar o al terminar de descargarlo), completa los
 *   libros que se quedaron sin vectores (`TEXT_READY`). Sin él, al menos les crea los fragmentos para
 *   que el chat pueda buscar por palabras.
 *
 * [StageCrashGuard] evita los bucles: no se encola una etapa que tumbó la app o que no funciona en
 * este móvil, salvo tras actualizar la app.
 */
@Singleton
class EmbeddingModelObserver @Inject constructor(
    private val models: ModelManager,
    private val bookDao: BookDao,
    private val scheduler: IndexScheduler,
    private val crashGuard: StageCrashGuard,
    @ApplicationScope private val scope: CoroutineScope
) {
    fun start() {
        scope.launch {
            // KEEP: si el trabajo sigue pendiente o en marcha no se toca; si ya no existe, se crea.
            IN_PROGRESS.flatMap { bookDao.idsWithStatus(it) }.forEach { scheduler.enqueue(it) }
            models.states
                .map { it[ModelCatalog.e5Small.id] is ModelState.Installed }
                .distinctUntilChanged()
                .collect { installed -> completeSearchIndex(installed) }
        }
    }

    private suspend fun completeSearchIndex(modelInstalled: Boolean) {
        val (ids, stage) = if (modelInstalled) {
            bookDao.idsWithStatus(IndexStatus.TEXT_READY) to StageCrashGuard.EMBEDDINGS
        } else {
            bookDao.idsWithStatusWithoutChunks(IndexStatus.TEXT_READY) to StageCrashGuard.CHUNKS
        }
        ids.filterNot { crashGuard.shouldSkip(stage, it) }.forEach { scheduler.enqueue(it, replace = true) }
    }

    private companion object {
        val IN_PROGRESS = listOf(IndexStatus.PENDING, IndexStatus.EXTRACTING_TEXT, IndexStatus.EMBEDDING)
    }
}
