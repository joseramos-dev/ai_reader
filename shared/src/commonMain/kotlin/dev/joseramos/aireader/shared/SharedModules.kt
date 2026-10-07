package dev.joseramos.aireader.shared

import dev.joseramos.aireader.ai.characters.charactersModule
import dev.joseramos.aireader.ai.embeddings.embeddingsModule
import dev.joseramos.aireader.ai.llm.llmModule
import dev.joseramos.aireader.ai.models.BundledModelsInstaller
import dev.joseramos.aireader.ai.models.modelsModule
import dev.joseramos.aireader.ai.rag.EmbeddingModelObserver
import dev.joseramos.aireader.ai.rag.ragModule
import dev.joseramos.aireader.core.common.commonModule
import dev.joseramos.aireader.core.data.di.dataModule
import dev.joseramos.aireader.feature.characters.charactersFeatureModule
import dev.joseramos.aireader.feature.chat.chatModule
import dev.joseramos.aireader.feature.library.libraryModule
import dev.joseramos.aireader.feature.reader.readerModule
import dev.joseramos.aireader.feature.settings.settingsModule
import dev.joseramos.aireader.indexing.ApiKeyObserver
import dev.joseramos.aireader.indexing.TextUpgradeObserver
import dev.joseramos.aireader.indexing.indexingModule
import dev.joseramos.aireader.pdf.pdfModule
import dev.joseramos.aireader.tts.ttsModule
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Lo que hay que hacer una vez al arrancar la app, en cualquier plataforma: instalar los modelos empaquetados y
 * poner en marcha los observadores que vuelven a encolar la indexación de los libros que lo necesiten.
 */
class AppStartup(
    private val bundledModels: BundledModelsInstaller,
    private val embeddingModels: EmbeddingModelObserver,
    private val textUpgrades: TextUpgradeObserver,
    private val apiKeys: ApiKeyObserver
) {
    fun start() {
        bundledModels.start()
        embeddingModels.start()
        textUpgrades.start()
        apiKeys.start()
    }
}

private val appShellModule = module {
    singleOf(::AppStartup)
    viewModelOf(::MainViewModel)
}

/**
 * Todos los módulos de Koin de la app. Cada plataforma añade los suyos (como mínimo, un `AppDirs` y un `AppInfo`)
 * al arrancar Koin.
 */
val sharedModules = listOf(
    commonModule,
    dataModule,
    modelsModule,
    embeddingsModule,
    pdfModule,
    indexingModule,
    llmModule,
    ragModule,
    charactersModule,
    ttsModule,
    libraryModule,
    readerModule,
    chatModule,
    charactersFeatureModule,
    settingsModule,
    appShellModule
)
