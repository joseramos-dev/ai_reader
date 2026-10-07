package dev.joseramos.aireader.indexing

import dev.joseramos.aireader.core.common.ApplicationScope
import dev.joseramos.aireader.core.common.IoDispatcher
import org.koin.androidx.workmanager.dsl.worker
import org.koin.dsl.module

/**
 * Las etapas que aportan otros módulos (IA, embeddings, personajes) se piden con `getOrNull()`: la
 * indexación las usa si están presentes.
 */
val indexingModule = module {
    single { IndexScheduler(get()) }
    single { StageCrashGuard(get()) }
    single { BookImporter(get(), get(), get(), get(), get(IoDispatcher)) }
    factory { TextIndexer(get(), get(), get(), get()) }
    factory { ChapterDetector(get(), get(), get(), getOrNull()) }
    factory { DocumentTypeDetector(get(), get(), get(), getOrNull()) }
    single { TextUpgradeObserver(get(), get(), get(ApplicationScope)) }
    single { ApiKeyObserver(get(), get(), get(), get(ApplicationScope)) }
    worker { IndexWorker(get(), get(), get(), get(), get(), get(), getOrNull(), getOrNull(), get(), get()) }
}
