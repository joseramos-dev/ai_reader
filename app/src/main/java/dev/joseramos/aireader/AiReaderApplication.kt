package dev.joseramos.aireader

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.joseramos.aireader.ai.models.BundledModelsInstaller
import dev.joseramos.aireader.ai.rag.EmbeddingModelObserver
import dev.joseramos.aireader.indexing.TextUpgradeObserver
import javax.inject.Inject

@HiltAndroidApp
class AiReaderApplication :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var embeddingModelObserver: EmbeddingModelObserver

    @Inject lateinit var textUpgradeObserver: TextUpgradeObserver

    @Inject lateinit var bundledModelsInstaller: BundledModelsInstaller

    override fun onCreate() {
        super.onCreate()
        // DJL (tokenizador de los embeddings) guarda una caché nativa; en Android no hay carpeta de usuario.
        System.setProperty("DJL_CACHE_DIR", cacheDir.absolutePath)
        System.setProperty("ENGINE_CACHE_DIR", cacheDir.absolutePath)
        bundledModelsInstaller.start()
        embeddingModelObserver.start()
        textUpgradeObserver.start()
    }

    // Los workers (indexación, descargas) reciben sus dependencias por Hilt.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
