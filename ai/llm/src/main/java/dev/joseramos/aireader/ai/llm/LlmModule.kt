package dev.joseramos.aireader.ai.llm

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.joseramos.aireader.indexing.LlmChapterDetection
import dev.joseramos.aireader.indexing.LlmDocumentClassification

@Module
@InstallIn(SingletonComponent::class)
interface LlmModule {
    @Binds
    fun llmClient(impl: ClaudeLlmClient): LlmClient

    /** Activa el paso con IA de la detección de capítulos de la indexación. */
    @Binds
    fun chapterDetection(impl: ClaudeChapterDetection): LlmChapterDetection

    /** Activa la confirmación con IA del tipo de documento. */
    @Binds
    fun documentClassification(impl: ClaudeDocumentClassification): LlmDocumentClassification
}
