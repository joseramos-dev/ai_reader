package dev.joseramos.aireader.ai.characters

import dev.joseramos.aireader.indexing.CharacterAnalysisTrigger
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/** Lo que aporta cada plataforma: el [CharacterScanScheduler] (WorkManager en Android, corrutinas en escritorio). */
expect val charactersPlatformModule: Module

val charactersModule = module {
    includes(charactersPlatformModule)

    factory { CharacterMerger(get(), get()) }
    factory { CharacterExtractor(get(), get(), get(), get(), get()) }
    factory { CharacterBrowser(get(), get(), get()) }
    factory { CharacterScanRunner(get(), get(), get(), get(), get(), get()) }
    single { CharacterAnalysis(get(), get(), get(), get(), get(), get(), get(), get()) }

    // Activa el arranque del análisis al terminar la indexación.
    factory { IndexedBookTrigger(get()) } bind CharacterAnalysisTrigger::class
}
