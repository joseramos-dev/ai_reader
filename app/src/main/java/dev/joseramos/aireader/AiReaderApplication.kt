package dev.joseramos.aireader

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.joseramos.aireader.ai.models.BundledModelsInstaller
import dev.joseramos.aireader.ai.rag.EmbeddingModelObserver
import dev.joseramos.aireader.indexing.ApiKeyObserver
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

    @Inject lateinit var apiKeyObserver: ApiKeyObserver

    override fun onCreate() {
        super.onCreate()
        bundledModelsInstaller.start()
        embeddingModelObserver.start()
        textUpgradeObserver.start()
        apiKeyObserver.start()
    }

    // Los workers (indexación, descargas) reciben sus dependencias por Hilt.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
