package dev.joseramos.aireader.ai.rag

import dev.joseramos.aireader.ai.llm.LlmClient
import dev.joseramos.aireader.ai.llm.LlmEvent
import dev.joseramos.aireader.ai.llm.LlmRequest
import dev.joseramos.aireader.ai.llm.LlmResponse
import dev.joseramos.aireader.ai.llm.LlmUsage
import dev.joseramos.aireader.ai.llm.Thinking
import dev.joseramos.aireader.core.data.book.Chapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiLocationJudgeTest {
    private val chapters = listOf(
        ChapterEvents(Chapter(7, 7, "Capítulo 7", 60, 70), listOf("Raskólnikov mata a la usurera.")),
        ChapterEvents(Chapter(9, 9, "Capítulo 9", 80, 90), listOf("Raskólnikov conoce a Sonia.", "Marmeladov muere."))
    )

    @Test
    fun readsWhetherItIsAPartOfTheBook() {
        assertEquals(true, GeminiLocationJudge.parseBookPart("""{"parte_del_libro": true}"""))
        assertEquals(false, GeminiLocationJudge.parseBookPart("```json\n{\"parte_del_libro\": false}\n```"))
        assertNull(GeminiLocationJudge.parseBookPart("No lo sé"))
        assertNull(GeminiLocationJudge.parseBookPart("""{"otra_cosa": 1}"""))
    }

    @Test
    fun readsWhereTheEventHappens() {
        assertEquals(
            EventAnchor(9, EventRelation.BEFORE),
            GeminiLocationJudge.parseEvent("""{"relacion": "ANTES", "capitulo": 2}""", chapters)
        )
        assertEquals(
            EventAnchor(7, EventRelation.AFTER),
            GeminiLocationJudge.parseEvent("""{"relacion": "despues", "capitulo": 1}""", chapters)
        )
        assertNull(GeminiLocationJudge.parseEvent("""{"relacion": "NINGUNA", "capitulo": 0}""", chapters))
        // Un capítulo que no está en la lista no vale.
        assertNull(GeminiLocationJudge.parseEvent("""{"relacion": "DESPUES", "capitulo": 5}""", chapters))
        assertNull(GeminiLocationJudge.parseEvent("roto", chapters))
    }

    /** Si se equivoca de número, manda el hecho que ha copiado (aunque sea sin tildes o a medias). */
    @Test
    fun theQuotedEventWinsOverTheNumber() {
        assertEquals(
            EventAnchor(7, EventRelation.AFTER),
            GeminiLocationJudge.parseEvent(
                """{"hecho": "Raskolnikov mata a la usurera", "capitulo": 2, "relacion": "DESPUES"}""",
                chapters
            )
        )
        // Un hecho que no está en la lista no cuenta: se usa el número.
        assertEquals(
            EventAnchor(9, EventRelation.AFTER),
            GeminiLocationJudge.parseEvent("""{"hecho": "Otra cosa", "capitulo": 2, "relacion": "DESPUES"}""", chapters)
        )
    }

    @Test
    fun theEventListNumbersTheChapters() {
        assertEquals(
            "[1] Capítulo 7 (p. 60–70)\n- Raskólnikov mata a la usurera.\n\n" +
                "[2] Capítulo 9 (p. 80–90)\n- Raskólnikov conoce a Sonia.\n- Marmeladov muere.",
            GeminiLocationJudge.eventList(chapters)
        )
    }

    /** Una llamada corta: con esquema JSON y sin razonar. */
    @Test
    fun asksForJsonWithoutThinking() = runTest {
        val llm = FakeLlm("""{"parte_del_libro": true}""")

        assertEquals(true, GeminiLocationJudge.askBookPart(llm, "modelo", "¿es parte del libro?"))

        val request = llm.requests.single()
        assertEquals("modelo", request.model)
        assertEquals(Thinking.MINIMAL, request.thinking)
        assertNotNull(request.jsonSchema)
    }

    private class FakeLlm(private val answer: String) : LlmClient {
        val requests = mutableListOf<LlmRequest>()

        override fun streamChat(request: LlmRequest): Flow<LlmEvent> = emptyFlow()

        override suspend fun complete(request: LlmRequest): LlmResponse {
            requests += request
            return LlmResponse(answer, LlmUsage())
        }

        override suspend fun hasApiKey() = true
    }
}
