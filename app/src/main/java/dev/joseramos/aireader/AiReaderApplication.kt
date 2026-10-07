package dev.joseramos.aireader

import android.app.Application
import dev.joseramos.aireader.ai.models.BundledModelsInstaller
import dev.joseramos.aireader.ai.rag.EmbeddingModelObserver
import dev.joseramos.aireader.core.common.AndroidLogSink
import dev.joseramos.aireader.core.common.Log
import dev.joseramos.aireader.indexing.ApiKeyObserver
import dev.joseramos.aireader.indexing.TextUpgradeObserver
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

class AiReaderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.sink = AndroidLogSink
        startKoin {
            androidContext(this@AiReaderApplication)
            // Los workers (indexación, personajes) reciben sus dependencias de Koin.
            workManagerFactory()
            modules(appModules)
        }
        get<BundledModelsInstaller>().start()
        get<EmbeddingModelObserver>().start()
        get<TextUpgradeObserver>().start()
        get<ApiKeyObserver>().start()
    }
}
