package dev.joseramos.aireader.ai.rag

import dev.joseramos.aireader.ai.llm.LlmClient
import dev.joseramos.aireader.ai.llm.LlmEvent
import dev.joseramos.aireader.ai.llm.LlmRequest
import dev.joseramos.aireader.ai.llm.LlmResponse
import dev.joseramos.aireader.ai.llm.LlmRole
import dev.joseramos.aireader.ai.llm.LlmUsage
import dev.joseramos.aireader.ai.llm.Prompts
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.settings.AppSettings
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Los casos dudosos contra Gemini de verdad, con los mismos prompts y esquemas que la app. Solo se
 * ejecuta con la clave en la variable de entorno `GEMINI_API_KEY` (y, si se quiere otro modelo que el
 * de resúmenes por defecto, `GEMINI_MODEL`); si no, se salta:
 *
 *     GEMINI_API_KEY=… ./gradlew :ai:rag:testDebugUnitTest --tests "*LocationJudgeLiveTest" --rerun
 *
 * Cada caso se imprime con su resultado; el test falla si alguno no es el esperado.
 */
class LocationJudgeLiveTest {
    private val key: String? = System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
    private val model = System.getenv("GEMINI_MODEL")?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_ANALYSIS_MODEL

    @Before
    fun requireKey() = assumeTrue("Sin GEMINI_API_KEY: no se llama a Gemini", key != null)

    /** ¿La expresión dudosa es una parte del libro? */
    private val bookPartCases = listOf(
        "¿Qué hace Raskólnikov al final?" to true,
        "¿Dónde vive Raskólnikov al principio?" to true,
        "¿Cómo está Sonia a la mitad?" to true,
        "¿Con quién habla Raskólnikov al inicio?" to true,
        "¿Qué pasa al final de la historia?" to true,
        "¿Qué tiene Raskólnikov al principio de su mano?" to false,
        "¿Qué hay al final de la calle?" to false,
        "¿Qué hace con la mitad del dinero?" to false,
        "¿Qué piensa al principio de su vida en San Petersburgo?" to false,
        "¿Qué dice al principio de la carta de su madre?" to false,
        "¿Quién vive al final del pasillo?" to false,
        "¿Quién espera a mitad de la escalera?" to false,
        "¿Qué dice en la segunda parte de su artículo?" to false,
        "¿Qué pasa en el último capítulo de su vida?" to false
    )

    /** Unos capítulos de *Crimen y castigo* con sus hechos, escritos a mano. */
    private val novel = listOf(
        events(1, "Parte 1. Capítulo 1", 2, 12, "Raskólnikov visita a la vieja usurera para empeñar un reloj."),
        events(2, "Capítulo 2", 13, 30, "En una taberna, Marmeladov le habla a Raskólnikov de su hija Sonia."),
        events(3, "Capítulo 3", 31, 45, "Raskólnikov recibe una carta de su madre: Dunia se casará con Luzhin."),
        events(7, "Capítulo 7", 80, 98, "Raskólnikov mata con un hacha a la usurera y a su hermana Lizaveta."),
        events(
            14,
            "Parte 2. Capítulo 7",
            170,
            190,
            "Marmeladov muere atropellado por un coche de caballos.",
            "Raskólnikov conoce a Sonia en casa de los Marmeladov."
        ),
        events(30, "Parte 6. Capítulo 8", 470, 480, "Raskólnikov confiesa el crimen en la comisaría.")
    )

    /** ¿Respecto a qué hecho, de qué capítulo, se sitúa la pregunta? `null`: no es un hecho de la lista. */
    private val eventCases = listOf(
        "¿Qué hace Raskólnikov después del crimen?" to EventAnchor(7, EventRelation.AFTER),
        "¿Qué pensaba Raskólnikov antes de conocer a Sonia?" to EventAnchor(14, EventRelation.BEFORE),
        "¿Qué hace Raskólnikov tras la muerte de Marmeladov?" to EventAnchor(14, EventRelation.AFTER),
        "¿Qué siente Raskólnikov después de confesar?" to EventAnchor(30, EventRelation.AFTER),
        "¿Qué hace Raskólnikov después de comer?" to null,
        "¿Qué siente Raskólnikov antes de dormirse?" to null
    )

    @Test
    fun decidesWhetherADoubtfulExpressionIsAPartOfTheBook() = runBlocking {
        val llm = GeminiRest(checkNotNull(key))
        val template = template("rag_location_v1")
        val failures = bookPartCases.mapNotNull { (question, expected) ->
            val candidate = LocationRules.detect(question, literature = true) as? LocationCandidate.Doubtful
                ?: return@mapNotNull "«$question»: las reglas no la marcan como dudosa"
            val prompt = Prompts.fill(template, "question" to question, "expression" to candidate.expression)
            val answer = GeminiLocationJudge.askBookPart(llm, model, prompt)
            report(answer == expected, "«${candidate.expression}» → $answer", question)
            "«$question» → $answer (se esperaba $expected)".takeIf { answer != expected }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun findsTheEventAmongTheFactsOfEachChapter() = runBlocking {
        val llm = GeminiRest(checkNotNull(key))
        val template = template("rag_event_v1")
        val failures = eventCases.mapNotNull { (question, expected) ->
            val candidate = LocationRules.detect(question, literature = true) as? LocationCandidate.Event
                ?: return@mapNotNull "«$question»: las reglas no ven un hecho"
            val prompt = Prompts.fill(
                template,
                "question" to question,
                "expression" to candidate.expression,
                "chapters" to GeminiLocationJudge.eventList(novel)
            )
            val answer = GeminiLocationJudge.askEvent(llm, model, prompt, novel)
            report(answer == expected, "«${candidate.expression}» → $answer", question)
            "«$question» → $answer (se esperaba $expected)".takeIf { answer != expected }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun report(ok: Boolean, result: String, question: String) =
        println("${if (ok) "OK " else "MAL"}  $result   [$question]")

    private fun template(name: String) = File("src/main/res/raw/$name.txt").readText()

    private fun events(id: Long, title: String, start: Int, end: Int, vararg events: String) =
        ChapterEvents(Chapter(id, id.toInt(), title, start, end), events.toList())

    /**
     * Cliente mínimo de la API REST de Gemini para este test: `generateContent` con el esquema y el
     * razonamiento de la petición, sin Android ni registro de consumo. Reintenta si está saturado.
     */
    private class GeminiRest(private val key: String) : LlmClient {
        private val http = HttpClient.newHttpClient()

        override fun streamChat(request: LlmRequest): Flow<LlmEvent> = throw UnsupportedOperationException()

        override suspend fun hasApiKey() = true

        override suspend fun complete(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                putJsonArray("contents") {
                    for (message in request.messages) {
                        addJsonObject {
                            put("role", if (message.role == LlmRole.USER) "user" else "model")
                            putJsonArray("parts") { addJsonObject { put("text", message.text) } }
                        }
                    }
                }
                putJsonObject("generationConfig") {
                    put("maxOutputTokens", request.maxTokens + request.thinking.margin)
                    putJsonObject("thinkingConfig") { put("thinkingLevel", request.thinking.name.lowercase()) }
                    if (request.jsonOutput || request.jsonSchema != null) put("responseMimeType", "application/json")
                    request.jsonSchema?.let { put("responseSchema", it) }
                }
            }
            val call = HttpRequest.newBuilder(URI("$BASE_URL/${request.model}:generateContent"))
                .header("x-goog-api-key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
            var response = http.send(call, HttpResponse.BodyHandlers.ofString())
            repeat(RETRIES) { attempt ->
                if (response.statusCode() !in RETRY_CODES) return@repeat
                delay(RETRY_DELAY_MS * (attempt + 1))
                response = http.send(call, HttpResponse.BodyHandlers.ofString())
            }
            check(response.statusCode() == HTTP_OK) { "Gemini respondió ${response.statusCode()}: ${response.body()}" }
            LlmResponse(text(response.body()), LlmUsage())
        }

        /** El texto de la respuesta, sin las partes de razonamiento. */
        private fun text(body: String): String {
            val candidate = Json.parseToJsonElement(body).jsonObject["candidates"]?.jsonArray?.firstOrNull()
            val parts = candidate?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()
            return parts.filter { it.jsonObject["thought"]?.jsonPrimitive?.booleanOrNull != true }
                .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
        }

        private companion object {
            const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
            const val HTTP_OK = 200
            val RETRY_CODES = setOf(429, 500, 503)
            const val RETRIES = 3
            const val RETRY_DELAY_MS = 3_000L
        }
    }
}
