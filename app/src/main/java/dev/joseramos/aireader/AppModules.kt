package dev.joseramos.aireader

import dev.joseramos.aireader.ai.characters.charactersModule
import dev.joseramos.aireader.ai.embeddings.embeddingsModule
import dev.joseramos.aireader.ai.llm.llmModule
import dev.joseramos.aireader.ai.models.modelsModule
import dev.joseramos.aireader.ai.rag.ragModule
import dev.joseramos.aireader.core.common.commonModule
import dev.joseramos.aireader.core.data.di.dataModule
import dev.joseramos.aireader.feature.characters.charactersFeatureModule
import dev.joseramos.aireader.feature.chat.chatModule
import dev.joseramos.aireader.feature.library.libraryModule
import dev.joseramos.aireader.feature.reader.readerModule
import dev.joseramos.aireader.feature.settings.settingsModule
import dev.joseramos.aireader.indexing.indexingModule
import dev.joseramos.aireader.tts.ttsModule
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

private val mainModule = module {
    viewModelOf(::MainViewModel)
}

/** Todos los módulos de Koin de la app. */
val appModules = listOf(
    commonModule,
    dataModule,
    modelsModule,
    embeddingsModule,
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
    mainModule
)
