package dev.joseramos.aireader.ai.embeddings

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface EmbeddingsModule {
    @Binds
    fun embedder(impl: E5Embedder): Embedder
}
