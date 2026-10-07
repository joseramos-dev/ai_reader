package dev.joseramos.aireader.ai.llm

import dev.joseramos.aireader.core.common.ApplicationScope
import dev.joseramos.aireader.core.common.IoDispatcher
import dev.joseramos.aireader.indexing.LlmChapterDetection
import dev.joseramos.aireader.indexing.LlmDocumentClassification
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/** Lo que aporta cada plataforma: el [BudgetNotifier]. */
expect val llmPlatformModule: Module

val llmModule = module {
    includes(llmPlatformModule)

    single { Prompts() }
    single { GeminiLlmClient(get(), get(), get(), get(IoDispatcher)) } bind LlmClient::class
    single { KeyPointsGenerator(get(), get(), get(), get(), get(), get(), get(ApplicationScope)) }

    // Activan los pasos con IA de la indexación (detección de capítulos y tipo de documento).
    factory { AiChapterDetection(get(), get(), get()) } bind LlmChapterDetection::class
    factory { AiDocumentClassification(get(), get(), get()) } bind LlmDocumentClassification::class
}
