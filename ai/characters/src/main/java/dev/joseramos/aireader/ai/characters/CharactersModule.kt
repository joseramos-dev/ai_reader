package dev.joseramos.aireader.ai.characters

import dev.joseramos.aireader.indexing.CharacterAnalysisTrigger
import org.koin.androidx.workmanager.dsl.worker
import org.koin.dsl.bind
import org.koin.dsl.module

val charactersModule = module {
    factory { CharacterMerger(get(), get()) }
    factory { CharacterExtractor(get(), get(), get(), get(), get()) }
    factory { CharacterBrowser(get(), get(), get()) }
    single { CharacterAnalysis(get(), get(), get(), get(), get(), get(), get(), get()) }

    // Activa el arranque del análisis al terminar la indexación.
    factory { IndexedBookTrigger(get()) } bind CharacterAnalysisTrigger::class
    worker { CharacterScanWorker(get(), get(), get(), get(), get(), get(), get(), get()) }
}
