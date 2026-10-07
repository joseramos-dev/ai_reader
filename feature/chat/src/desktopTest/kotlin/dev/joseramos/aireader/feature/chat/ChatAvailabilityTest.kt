package dev.joseramos.aireader.feature.chat

import dev.joseramos.aireader.ai.models.ModelState
import dev.joseramos.aireader.core.data.db.IndexStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatAvailabilityTest {
    private val installed = ModelState.Installed(sizeBytes = 1)

    private fun availability(
        status: IndexStatus?,
        progress: Float = 0.5f,
        hasKey: Boolean = true,
        model: ModelState = installed,
        hasChunks: Boolean = true
    ) = chatAvailability(status, progress, hasKey, model, hasChunks)

    @Test
    fun withoutKeyAsksForIt() {
        assertEquals(ChatAvailability.NoApiKey, availability(IndexStatus.READY, hasKey = false))
    }

    @Test
    fun readyWithVectorsUsesFullSearch() {
        assertEquals(ChatAvailability.Ready(SearchQuality.Full), availability(IndexStatus.READY, 1f))
    }

    /** El fallo del teléfono: embeddings fallidos con el modelo instalado ya no se quedan «al 50 %». */
    @Test
    fun textReadyWithModelInstalledLetsAskWithWordSearch() {
        assertEquals(ChatAvailability.Ready(SearchQuality.Unsupported), availability(IndexStatus.TEXT_READY))
    }

    @Test
    fun textReadyWithoutModelLetsAskAndOffersDownload() {
        val model = ModelState.NotInstalled
        assertEquals(
            ChatAvailability.Ready(SearchQuality.ModelMissing(model)),
            availability(IndexStatus.TEXT_READY, model = model)
        )
    }

    @Test
    fun embeddingInProgressWithChunksLetsAskWhileImproving() {
        assertEquals(
            ChatAvailability.Ready(SearchQuality.Improving(0.5f)),
            availability(IndexStatus.EMBEDDING, progress = 0.75f)
        )
    }

    @Test
    fun indexingWithoutChunksShowsRealProgress() {
        assertEquals(ChatAvailability.Indexing(0.2f), availability(IndexStatus.EXTRACTING_TEXT, 0.2f))
        assertEquals(ChatAvailability.Indexing(0.5f), availability(IndexStatus.EMBEDDING, 0.5f, hasChunks = false))
    }

    @Test
    fun finishedWithoutChunksIsNotShownAsLoading() {
        assertEquals(ChatAvailability.Unavailable, availability(IndexStatus.TEXT_READY, hasChunks = false))
    }

    @Test
    fun emptyReadyBookCanStillBeAsked() {
        assertEquals(ChatAvailability.Ready(), availability(IndexStatus.READY, 1f, hasChunks = false))
    }

    @Test
    fun failedWithoutChunksIsFailed() {
        assertEquals(ChatAvailability.Failed, availability(IndexStatus.FAILED, 0f, hasChunks = false))
    }

    @Test
    fun unknownBookIsLoading() {
        assertEquals(ChatAvailability.Loading, availability(null))
    }
}
