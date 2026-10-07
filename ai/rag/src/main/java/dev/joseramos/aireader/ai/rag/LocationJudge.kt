package dev.joseramos.aireader.ai.rag

import dev.joseramos.aireader.ai.llm.LlmClient
import dev.joseramos.aireader.ai.llm.LlmMessage
import dev.joseramos.aireader.ai.llm.LlmRequest
import dev.joseramos.aireader.ai.llm.LlmRole
import dev.joseramos.aireader.ai.llm.Prompts
import dev.joseramos.aireader.ai.llm.ResponseSchema
import dev.joseramos.aireader.ai.llm.Thinking
import dev.joseramos.aireader.core.common.Log
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Hechos importantes de un capítulo, para situar en el libro un hecho del que habla una pregunta. */
data class ChapterEvents(val chapter: Chapter, val events: List<String>)

/** Capítulo donde ocurre el hecho de una pregunta y si se pregunta por lo de antes, durante o después. */
data class EventAnchor(val chapterId: Long, val relation: EventRelation)

/** Decide lo que no pueden las reglas: si una expresión dudosa es una parte del libro y dónde ocurre un hecho. */
interface LocationJudge {
    /** Si [expression] («al principio de su mano») es una parte del libro; `null` si no se ha podido decidir. */
    suspend fun refersToBook(question: String, expression: String): Boolean?

    /** Dónde ocurre el hecho de [expression] entre [chapters]; `null` si no está o no se ha podido decidir. */
    suspend fun locateEvent(question: String, expression: String, chapters: List<ChapterEvents>): EventAnchor?
}

/**
 * [LocationJudge] con el modelo de análisis (Gemini Flash-Lite por defecto): una llamada corta, con
 * respuesta JSON y sin razonamiento. Si falla (sin clave, sin red, respuesta rara) no decide, y la
 * pregunta se responde como si no hablara de ninguna parte del libro.
 */
class GeminiLocationJudge(
    private val llm: LlmClient,
    private val prompts: Prompts,
    private val settings: SettingsRepository
) : LocationJudge {
    override suspend fun refersToBook(question: String, expression: String): Boolean? = safely {
        val prompt = prompts.render(R.raw.rag_location_v1, "question" to question, "expression" to expression)
        askBookPart(llm, model(), prompt)
    }

    override suspend fun locateEvent(
        question: String,
        expression: String,
        chapters: List<ChapterEvents>
    ): EventAnchor? = if (chapters.isEmpty()) {
        null
    } else {
        safely {
            val prompt = prompts.render(
                R.raw.rag_event_v1,
                "question" to question,
                "expression" to expression,
                "chapters" to eventList(chapters)
            )
            askEvent(llm, model(), prompt, chapters)
        }
    }

    private suspend fun model() = settings.settings.first().analysisModel

    @Suppress("TooGenericExceptionCaught") // Un fallo de la llamada deja la pregunta sin situar, no la rompe.
    private suspend fun <T> safely(block: suspend () -> T?): T? {
        if (!llm.hasApiKey()) return null
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No se ha podido situar la pregunta en el libro", e)
            null
        }
    }

    internal companion object {
        private const val TAG = "GeminiLocationJudge"
        private const val MAX_TOKENS = 200L
        private val json = Json { ignoreUnknownKeys = true }

        private val BOOK_PART_SCHEMA = ResponseSchema.obj("parte_del_libro" to ResponseSchema.boolean)

        /** Primero copia el hecho de la lista y luego dice el capítulo: así se equivoca menos de número. */
        private val EVENT_SCHEMA = ResponseSchema.obj(
            "hecho" to ResponseSchema.string(),
            "capitulo" to ResponseSchema.integer,
            "relacion" to ResponseSchema.string(listOf("ANTES", "DURANTE", "DESPUES", "NINGUNA")),
            ordered = true
        )
        private const val MIN_QUOTE_CHARS = 10
        private val RELATIONS = mapOf(
            "ANTES" to EventRelation.BEFORE,
            "DURANTE" to EventRelation.DURING,
            "DESPUES" to EventRelation.AFTER
        )

        @Serializable
        private data class BookPartAnswer(@SerialName("parte_del_libro") val bookPart: Boolean? = null)

        @Serializable
        private data class EventAnswer(
            @SerialName("hecho") val event: String = "",
            @SerialName("relacion") val relation: String = "",
            @SerialName("capitulo") val chapter: Int = 0
        )

        /** Pregunta a [llm], con [prompt] (de `rag_location_v1`), si la expresión es una parte del libro. */
        suspend fun askBookPart(llm: LlmClient, model: String, prompt: String): Boolean? =
            parseBookPart(complete(llm, model, prompt, BOOK_PART_SCHEMA))

        /** Pregunta a [llm], con [prompt] (de `rag_event_v1`, con la [eventList] de [chapters]), dónde ocurre. */
        suspend fun askEvent(
            llm: LlmClient,
            model: String,
            prompt: String,
            chapters: List<ChapterEvents>
        ): EventAnchor? = parseEvent(complete(llm, model, prompt, EVENT_SCHEMA), chapters)

        /** Los capítulos numerados desde 1, cada uno con sus hechos: «[1] Capítulo 1 (p. 2–8)» y «- …». */
        fun eventList(chapters: List<ChapterEvents>): String = chapters.mapIndexed { i, (chapter, events) ->
            val heading = "[${i + 1}] ${chapter.title} (p. ${chapter.startPage}–${chapter.endPage})"
            heading + events.joinToString("") { "\n- $it" }
        }.joinToString("\n\n")

        fun parseBookPart(answer: String): Boolean? = decode<BookPartAnswer>(answer)?.bookPart

        /**
         * El capítulo y la relación; `null` si es NINGUNA o el capítulo no existe. El capítulo es el del
         * hecho que ha copiado, si se encuentra en la lista; si no, el de su número en la [eventList].
         */
        fun parseEvent(answer: String, chapters: List<ChapterEvents>): EventAnchor? {
            val parsed = decode<EventAnswer>(answer) ?: return null
            val relation = RELATIONS[parsed.relation.trim().uppercase()] ?: return null
            val quoted = chapters.firstOrNull { it.events.any { event -> sameEvent(event, parsed.event) } }
            val chapter = (quoted ?: chapters.getOrNull(parsed.chapter - 1))?.chapter ?: return null
            return EventAnchor(chapter.id, relation)
        }

        /** Si [quote] es [event] (o un trozo largo de él), sin contar mayúsculas, tildes ni puntuación. */
        private fun sameEvent(event: String, quote: String): Boolean {
            val a = comparable(event)
            val b = comparable(quote)
            return b.length >= MIN_QUOTE_CHARS && (a.contains(b) || b.contains(a))
        }

        private fun comparable(text: String) = LocationText.fold(text).replace(Regex("""[^\p{L}\d]+"""), " ").trim()

        private suspend fun complete(llm: LlmClient, model: String, prompt: String, schema: JsonObject): String =
            llm.complete(
                LlmRequest(
                    model = model,
                    messages = listOf(LlmMessage(LlmRole.USER, prompt)),
                    maxTokens = MAX_TOKENS,
                    thinking = Thinking.MINIMAL,
                    jsonSchema = schema
                )
            ).text

        private inline fun <reified T> decode(answer: String): T? {
            val start = answer.indexOf('{')
            val end = answer.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { json.decodeFromString<T>(answer.substring(start, end + 1)) }.getOrNull()
        }
    }
}
