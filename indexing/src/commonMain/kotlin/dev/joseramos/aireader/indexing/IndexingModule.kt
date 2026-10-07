package dev.joseramos.aireader.indexing

import dev.joseramos.aireader.core.common.ApplicationScope
import dev.joseramos.aireader.core.common.IoDispatcher
import org.koin.core.module.Module
import org.koin.dsl.module

/** Lo que aporta cada plataforma: el [IndexScheduler] (WorkManager en Android, corrutinas en escritorio). */
expect val indexingPlatformModule: Module

/**
 * Las etapas que aportan otros módulos (IA, embeddings, personajes) se piden con `getOrNull()`: la
 * indexación las usa si están presentes.
 */
val indexingModule = module {
    includes(indexingPlatformModule)

    single { StageCrashGuard(get(), get()) }
    single { BookImporter(get(), get(), get(), get(), get(IoDispatcher)) }
    factory { TextIndexer(get(), get(), get(), get()) }
    factory { ChapterDetector(get(), get(), get(), getOrNull()) }
    factory { DocumentTypeDetector(get(), get(), get(), getOrNull()) }
    factory { IndexRunner(get(), get(), get(), get(), getOrNull(), getOrNull(), get(), get()) }
    single { TextUpgradeObserver(get(), get(), get(ApplicationScope)) }
    single { ApiKeyObserver(get(), get(), get(), get(ApplicationScope)) }
}
