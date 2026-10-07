package dev.joseramos.aireader.indexing

import org.koin.androidx.workmanager.dsl.worker
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

actual val indexingPlatformModule: Module = module {
    single { WorkManagerIndexScheduler(get()) } bind IndexScheduler::class
    worker { IndexWorker(get(), get(), get(), get()) }
}
