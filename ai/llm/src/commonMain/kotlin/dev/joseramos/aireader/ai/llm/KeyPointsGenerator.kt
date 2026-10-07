package dev.joseramos.aireader.ai.llm

import dev.joseramos.aireader.core.common.Log
import dev.joseramos.aireader.core.data.book.BookContentRepository
import dev.joseramos.aireader.core.data.book.BookRepository
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.ChapterKeyPoints
import dev.joseramos.aireader.core.data.book.KeyPoint
import dev.joseramos.aireader.core.data.book.KeyPointsRepository
import dev.joseramos.aireader.core.data.db.DocumentType
import dev.joseramos.aireader.core.data.db.KeyPointsStatus
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Trabajo de IA que muestra el lector: los hechos clave de un capítulo ([chapterId]) o el repaso (`null`). */
data class KeyPointsJobKey(val bookId: String, val chapterId: Long?)

sealed interface KeyPointsJob {
    data object Running : KeyPointsJob

    data class Failed(val message: String, val needsApiKey: Boolean) : KeyPointsJob
}

/** Repaso «Hasta ahora…» de lo leído hasta [untilPage]. Solo se guarda en memoria: se genera barato. */
data class Recap(val text: String, val untilPage: Int)

/**
 * Hechos clave (novelas) o ideas clave (el resto) de cada capítulo: frases esquemáticas, en orden, cada
 * una con la página donde ocurre. Sustituyen a los resúmenes: el chat los usa para las preguntas sobre
 * el libro en conjunto o sobre una parte, y el repaso «Hasta ahora…» se escribe a partir de ellos.
 *
 * Se generan solo cuando hacen falta ([ensure]), con el modelo de análisis (Gemini Flash-Lite por
 * defecto), y se guardan por capítulo. El texto del capítulo va con una marca `[p. N]` al principio de
 * cada página, y el modelo responde en JSON con la página de cada punto. Un capítulo largo se parte por
 * páginas enteras en bloques de ≈30 k tokens y sus puntos se juntan en orden.
 */
class KeyPointsGenerator(
    private val llm: LlmClient,
    private val prompts: Prompts,
    private val content: BookContentRepository,
    private val books: BookRepository,
    private val keyPoints: KeyPointsRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope
) {
    private val _jobs = MutableStateFlow<Map<KeyPointsJobKey, KeyPointsJob>>(emptyMap())
    val jobs: StateFlow<Map<KeyPointsJobKey, KeyPointsJob>> = _jobs

    private val _recaps = MutableStateFlow<Map<String, Recap>>(emptyMap())

    /** Último repaso de cada libro, por id de libro. */
    val recaps: StateFlow<Map<String, Recap>> = _recaps

    /** Un capítulo no se genera dos veces a la vez (el chat y la hoja del lector pueden pedirlo juntos). */
    private val chapterLocks = mutableMapOf<Long, Mutex>()

    /**
     * Para la hoja de un capítulo: genera sus hechos clave si faltan (o de nuevo, con [replace]), con el
     * estado en [jobs]. Corre en el ámbito de la app para que cerrar la hoja no lo cancele.
     */
    fun generate(bookId: String, chapter: Chapter, replace: Boolean = false) {
        launchJob(KeyPointsJobKey(bookId, chapter.id)) {
            if (replace) keyPoints.delete(chapter.id)
            chapterKeyPoints(bookId, chapter)
        }
    }

    /** Los capítulos de [chapters] que aún no tienen hechos clave. */
    suspend fun missing(bookId: String, chapters: List<Chapter>): List<Chapter> {
        val saved = keyPoints.all(bookId)
        return chapters.filter { it.id !in saved }
    }

    /**
     * Los hechos clave de [chapters], generando los que falten (hasta [MAX_PARALLEL] a la vez) y avisando
     * con [onProgress] de cuántos de los que faltaban van listos. Si un capítulo falla (Gemini saturado,
     * sin red…), se queda sin ellos y quien llama decide qué hacer; sin clave válida, se lanza el error.
     */
    suspend fun ensure(
        bookId: String,
        chapters: List<Chapter>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Map<Long, ChapterKeyPoints> {
        val pending = missing(bookId, chapters)
        if (pending.isNotEmpty()) {
            onProgress(0, pending.size)
            val permits = Semaphore(MAX_PARALLEL)
            coroutineScope {
                // El progreso se avisa desde aquí y no desde cada capítulo: quien llama puede estar
                // emitiendo en un Flow, que no admite emisiones desde otras corrutinas.
                val finished = Channel<Unit>(Channel.UNLIMITED)
                pending.forEach { chapter ->
                    launch {
                        permits.withPermit { tryChapter(bookId, chapter) }
                        finished.send(Unit)
                    }
                }
                repeat(pending.size) { done ->
                    finished.receive()
                    onProgress(done + 1, pending.size)
                }
            }
        }
        val saved = keyPoints.all(bookId)
        return chapters.mapNotNull { chapter -> saved[chapter.id]?.let { chapter.id to it } }.toMap()
    }

    /** Estimación local (sin llamar a la API) de lo que costaría generar los que faltan de [chapters]. */
    suspend fun estimate(bookId: String, chapters: List<Chapter>): TokenEstimate {
        var estimate = TokenEstimate()
        for (chapter in missing(bookId, chapters)) {
            val tokens = TokenEstimate.tokensIn(content.text(bookId, chapter.startPage, chapter.endPage)).toLong()
            if (tokens > 0) estimate += chapterCost(tokens)
        }
        return estimate
    }

    /**
     * Repaso de lo leído hasta [untilPage] (incluida), a partir de los hechos clave de los capítulos que
     * empiezan antes, generando los que falten. Solo entran los que ocurren hasta esa página.
     */
    fun recap(bookId: String, untilPage: Int) {
        launchJob(KeyPointsJobKey(bookId, null)) {
            val chapters = chaptersUntil(bookId, untilPage)
            val saved = ensure(bookId, chapters)
            val points = recapPoints(chapters, saved, untilPage)
            if (points.isBlank()) throw LlmException.Failed(NOTHING_TO_RECAP)
            val literature = books.getBook(bookId)?.isLiterature == true
            val text = complete(
                prompts.render(
                    "key_points_recap_v1",
                    "book" to bookTitle(bookId),
                    "page" to untilPage,
                    "kind" to if (literature) "hechos" else "ideas",
                    "tag" to if (literature) "hechos_clave" else "ideas_clave",
                    "points" to points
                ),
                maxTokens = RECAP_MAX_TOKENS
            )
            _recaps.update { it + (bookId to Recap(text, untilPage)) }
        }
    }

    /** Estimación local de [recap]: los hechos clave que faltan y la llamada final. */
    suspend fun estimateRecap(bookId: String, untilPage: Int): TokenEstimate {
        val chapters = chaptersUntil(bookId, untilPage)
        val saved = keyPoints.all(bookId)
        val known = chapters.sumOf { chapter ->
            saved[chapter.id]?.until(untilPage)?.sumOf { TokenEstimate.tokensIn(it.text) + PAGE_TOKENS } ?: 0
        }.toLong()
        val missing = missing(bookId, chapters)
        return estimate(bookId, chapters) + TokenEstimate(
            TokenEstimate.PROMPT_OVERHEAD_TOKENS + known + missing.size * ESTIMATED_POINTS_TOKENS,
            ESTIMATED_RECAP_TOKENS
        )
    }

    private suspend fun chaptersUntil(bookId: String, page: Int) =
        content.chapters(bookId).filter { it.startPage <= page }

    /** Genera un capítulo para [ensure]: los fallos pasajeros no paran a los demás. */
    private suspend fun tryChapter(bookId: String, chapter: Chapter) {
        repeat(RATE_LIMIT_ATTEMPTS) { attempt ->
            try {
                chapterKeyPoints(bookId, chapter)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException.RateLimited) {
                // El nivel gratuito limita las peticiones por minuto: se espera y se vuelve a intentar.
                Log.w(TAG, "Límite de peticiones con el capítulo ${chapter.number}", e)
                delay(RATE_LIMIT_DELAY_MS * (attempt + 1))
            } catch (e: LlmException) {
                // Sin clave válida no tiene sentido seguir con los demás capítulos.
                if (e is LlmException.NoApiKey || e is LlmException.Unauthorized) throw e
                Log.w(TAG, "Sin hechos clave del capítulo ${chapter.number}", e)
                return
            }
        }
    }

    /** Los hechos clave de [chapter]: los guardados o unos nuevos, que se guardan. */
    private suspend fun chapterKeyPoints(bookId: String, chapter: Chapter): ChapterKeyPoints =
        synchronized(chapterLocks) { chapterLocks.getOrPut(chapter.id) { Mutex() } }.withLock {
            keyPoints.get(chapter.id)?.let { return@withLock it }
            val model = model()
            val pages = content.pagesFrom(bookId, chapter.startPage, chapter.endPage - chapter.startPage + 1)
                .filter { it.page <= chapter.endPage }
                .map { it.page to it.paragraphs.joinToString("\n\n") }
                .filter { (_, text) -> text.isNotBlank() }
            val blocks = pageBlocks(pages, BLOCK_TOKENS * CHARS_PER_TOKEN)
            val (status, points) = when {
                blocks.isEmpty() -> KeyPointsStatus.EMPTY to emptyList()
                else -> try {
                    KeyPointsStatus.READY to blocks.flatMapIndexed { i, block ->
                        blockPoints(bookId, chapter, block, i, blocks.size)
                    }
                } catch (e: LlmException.Refused) {
                    // No se vuelve a pedir en cada pregunta: se apunta y el chat usa el texto del capítulo.
                    Log.w(TAG, "Hechos clave del capítulo ${chapter.number} rechazados", e)
                    KeyPointsStatus.REFUSED to emptyList()
                }
            }
            keyPoints.save(bookId, chapter.id, status, points, model)
            ChapterKeyPoints(chapter.id, status, points)
        }

    private suspend fun blockPoints(
        bookId: String,
        chapter: Chapter,
        block: List<Pair<Int, String>>,
        index: Int,
        count: Int
    ): List<KeyPoint> {
        val book = books.getBook(bookId)
        val literature = book?.isLiterature == true
        val (min, max) = pointsRange(block.size)
        val part = if (count > 1) " (parte ${index + 1} de $count)" else ""
        val values = mutableListOf<Pair<String, Any>>(
            "book" to book?.title.orEmpty(),
            "chapter" to chapter.title,
            "part" to part,
            "min" to min,
            "max" to max,
            "text" to blockText(block)
        )
        if (!literature) values += "focus" to focus(book?.documentType)
        val prompt = prompts.render(
            if (literature) "key_points_events_v1" else "key_points_ideas_v1",
            *values.toTypedArray()
        )
        val answer = complete(prompt, maxTokens = POINTS_MAX_TOKENS, schema = SCHEMA)
        return parseKeyPoints(answer, block.first().first..block.last().first)
            ?: throw LlmException.Failed("Respuesta inesperada al preparar los hechos clave.")
    }

    /** Qué cuenta como idea clave según el tipo de documento. */
    private fun focus(type: DocumentType?): String = when (type) {
        DocumentType.SCIENTIFIC ->
            "el objetivo o la pregunta, el método, los resultados principales (con cifras si " +
                "las hay) y las conclusiones"
        DocumentType.EDUCATIONAL ->
            "los conceptos, definiciones e ideas principales, cómo se relacionan y los " +
                "ejemplos importantes, de forma que sirva para estudiar"
        else -> "los puntos principales, datos, fechas, decisiones o conclusiones"
    }

    private suspend fun complete(prompt: String, maxTokens: Long, schema: JsonObject? = null) = llm.complete(
        LlmRequest(
            model = model(),
            system = listOf(SystemBlock(prompts.render("key_points_system_v1"))),
            messages = listOf(LlmMessage(LlmRole.USER, prompt)),
            maxTokens = maxTokens,
            // Enumerar no necesita razonar: el razonamiento se cobra como salida en cada llamada.
            thinking = Thinking.MINIMAL,
            jsonSchema = schema
        )
    ).text.trim()

    private fun launchJob(key: KeyPointsJobKey, block: suspend () -> Unit) {
        if (_jobs.value[key] == KeyPointsJob.Running) return
        scope.launch { runCatching { runForKey(key, block) } }
    }

    /** Ejecuta [block] marcando [key] como en curso y, si falla, guarda el error para la UI. */
    private suspend fun runForKey(key: KeyPointsJobKey, block: suspend () -> Unit) {
        _jobs.update { it + (key to KeyPointsJob.Running) }
        val error = runCatching { block() }.exceptionOrNull()
        when (error) {
            null, is CancellationException -> _jobs.update { it - key }
            is LlmException -> {
                val needsApiKey = error is LlmException.NoApiKey || error is LlmException.Unauthorized
                _jobs.update { it + (key to KeyPointsJob.Failed(error.message.orEmpty(), needsApiKey)) }
            }
            else -> {
                Log.e(TAG, "Fallo en $key", error)
                _jobs.update { it + (key to KeyPointsJob.Failed(GENERIC_ERROR, false)) }
            }
        }
    }

    private suspend fun model() = settings.settings.first().analysisModel

    private suspend fun bookTitle(bookId: String) = books.getBook(bookId)?.title.orEmpty()

    companion object {
        private const val TAG = "KeyPointsGenerator"
        private const val CHARS_PER_TOKEN = 4
        private const val BLOCK_TOKENS = 30_000
        private const val MAX_PARALLEL = 3
        private const val RATE_LIMIT_ATTEMPTS = 3
        private const val RATE_LIMIT_DELAY_MS = 20_000L
        private const val POINTS_MAX_TOKENS = 4_000L
        private const val RECAP_MAX_TOKENS = 3_000L

        /** Lo que suelen ocupar los hechos clave de un capítulo y un repaso, para las estimaciones. */
        private const val ESTIMATED_POINTS_TOKENS = 300L
        private const val ESTIMATED_RECAP_TOKENS = 500L

        /** La página que acompaña a cada punto en el repaso («(p. 12)»). */
        private const val PAGE_TOKENS = 3
        private const val MIN_POINTS = 2
        private const val MAX_POINTS = 15
        private const val FEW_POINTS = 3
        private const val PAGES_PER_POINT = 2
        private const val GENERIC_ERROR = "No se pudieron preparar los hechos clave. Inténtalo de nuevo."
        private const val NOTHING_TO_RECAP = "No hay hechos clave de lo leído con los que hacer el repaso."
        private val json = Json { ignoreUnknownKeys = true }

        @Serializable
        private data class Answer(@SerialName("puntos") val points: List<Point> = emptyList())

        @Serializable
        private data class Point(@SerialName("texto") val text: String = "", @SerialName("pagina") val page: Int = 0)

        private val SCHEMA = ResponseSchema.obj(
            "puntos" to ResponseSchema.array(
                ResponseSchema.obj(
                    "texto" to ResponseSchema.string(),
                    "pagina" to ResponseSchema.integer,
                    ordered = true
                )
            )
        )

        /** Un capítulo de [tokens]: una llamada por bloque, con su lista de puntos. */
        internal fun chapterCost(tokens: Long): TokenEstimate {
            val blocks = (tokens + BLOCK_TOKENS - 1) / BLOCK_TOKENS
            return TokenEstimate(
                tokens + blocks * TokenEstimate.PROMPT_OVERHEAD_TOKENS,
                blocks * ESTIMATED_POINTS_TOKENS
            )
        }

        /**
         * Agrupa las páginas `(número, texto)` en bloques seguidos de como mucho [maxChars] (una página
         * más larga va sola): así un punto siempre está en el bloque de su página.
         */
        internal fun pageBlocks(pages: List<Pair<Int, String>>, maxChars: Int): List<List<Pair<Int, String>>> {
            val blocks = mutableListOf<List<Pair<Int, String>>>()
            var current = mutableListOf<Pair<Int, String>>()
            var size = 0
            for (page in pages) {
                if (current.isNotEmpty() && size + page.second.length > maxChars) {
                    blocks += current
                    current = mutableListOf()
                    size = 0
                }
                current += page
                size += page.second.length
            }
            if (current.isNotEmpty()) blocks += current
            return blocks
        }

        /** El texto de un bloque, con una marca `[p. N]` al principio de cada página. */
        internal fun blockText(block: List<Pair<Int, String>>): String =
            block.joinToString("\n\n") { (page, text) -> "[p. $page]\n$text" }

        /** Cuántos puntos pedir para un bloque de [pages] páginas: uno cada dos, entre 3 y 15. */
        internal fun pointsRange(pages: Int): Pair<Int, Int> {
            val max = ((pages + 1) / PAGES_PER_POINT).coerceIn(FEW_POINTS, MAX_POINTS)
            return (if (pages <= MIN_POINTS) 1 else MIN_POINTS) to max
        }

        /**
         * Los puntos de la respuesta JSON, sin los vacíos y con la página dentro de [pages] (el bloque);
         * `null` si la respuesta no se puede leer (por ejemplo, cortada).
         */
        internal fun parseKeyPoints(answer: String, pages: IntRange): List<KeyPoint>? {
            val start = answer.indexOf('{')
            val end = answer.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val parsed = runCatching { json.decodeFromString<Answer>(answer.substring(start, end + 1)) }.getOrNull()
            return parsed?.points
                ?.filter { it.text.isNotBlank() }
                ?.map { KeyPoint(it.text.trim(), it.page.coerceIn(pages.first, pages.last)) }
        }

        /**
         * Los hechos clave para el repaso, hasta [untilPage]: cada capítulo con su título y sus puntos
         * («- texto (p. N)»), sin los capítulos que no tienen ninguno.
         */
        internal fun recapPoints(chapters: List<Chapter>, saved: Map<Long, ChapterKeyPoints>, untilPage: Int): String =
            chapters.mapNotNull { chapter ->
                val points = saved[chapter.id]?.until(untilPage).orEmpty()
                points.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "${chapter.title}\n") {
                    "- ${it.text} (p. ${it.page})"
                }
            }.joinToString("\n\n")
    }
}
