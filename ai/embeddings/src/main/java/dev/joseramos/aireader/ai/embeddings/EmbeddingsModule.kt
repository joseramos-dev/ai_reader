package dev.joseramos.aireader.ai.embeddings

import dev.joseramos.aireader.core.common.DefaultDispatcher
import org.koin.dsl.bind
import org.koin.dsl.module

val embeddingsModule = module {
    single { E5Embedder(get(), get(DefaultDispatcher)) } bind Embedder::class
}
