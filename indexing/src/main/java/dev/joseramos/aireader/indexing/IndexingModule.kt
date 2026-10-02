package dev.joseramos.aireader.indexing

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Etapas que aportan otros módulos (IA) y que la indexación usa si están presentes. */
@Module
@InstallIn(SingletonComponent::class)
interface IndexingModule {
    @BindsOptionalOf
    fun llmChapterDetection(): LlmChapterDetection

    @BindsOptionalOf
    fun embeddingStage(): EmbeddingStage

    @BindsOptionalOf
    fun llmDocumentClassification(): LlmDocumentClassification

    @BindsOptionalOf
    fun characterAnalysisTrigger(): CharacterAnalysisTrigger
}
