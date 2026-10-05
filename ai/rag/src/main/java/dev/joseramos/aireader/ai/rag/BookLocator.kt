package dev.joseramos.aireader.ai.rag

import android.util.Log
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.SummaryRepository
import dev.joseramos.aireader.core.data.db.SummaryKind
import javax.inject.Inject

/** Lo que hace falta saber del libro y del lector para situar una pregunta. */
data class LocationContext(
    /** Capítulos (nivel 0), en orden de lectura. */
    val chapters: List<Chapter>,
    val pageCount: Int,
    /** En novelas también se sitúan las preguntas por un hecho («después del crimen»). */
    val literature: Boolean,
    val reading: ReadingContext
)

/** Pregunta situada en una parte del libro. */
data class LocatedQuestion(
    val location: BookLocation,
    /** Páginas a las que se refiere, ya recortadas por los anti-spoilers: vacío si el lector aún no ha llegado. */
    val pages: List<IntRange>,
    /** La pregunta sin la expresión que la sitúa, para buscar por palabras y por significado. */
    val query: String
)

/**
 * Sitúa una pregunta en el libro: las [LocationRules] encuentran la expresión, el [LocationJudge]
 * decide las dudosas («al principio de su mano») y busca los hechos («después del crimen») entre los
 * hechos guardados de cada capítulo, y el [LocationResolver] lo convierte en páginas.
 */
class BookLocator internal constructor(
    private val judge: LocationJudge,
    /** Texto de los hechos guardados de cada capítulo del libro, por id de capítulo. */
    private val savedEvents: suspend (bookId: String) -> Map<Long, String>
) {
    @Inject constructor(judge: LocationJudge, summaries: SummaryRepository) : this(
        judge,
        { bookId ->
            summaries.all(bookId)
                .filter { it.kind == SummaryKind.CHAPTER_EVENTS }
                .mapNotNull { summary -> summary.chapterId?.let { it to summary.text } }
                .toMap()
        }
    )

    /** Dónde se sitúa [question], o `null` si no habla de ninguna parte del libro (o no se ha podido situar). */
    suspend fun locate(bookId: String, question: String, context: LocationContext): LocatedQuestion? {
        val candidate = LocationRules.detect(question, context.literature) ?: return null
        val location = when (candidate) {
            is LocationCandidate.Clear -> candidate.location
            is LocationCandidate.Doubtful ->
                candidate.location.takeIf { judge.refersToBook(question, candidate.expression) == true }
            is LocationCandidate.Event -> event(bookId, question, candidate.expression, context)
        }
        val pages = location?.let {
            LocationResolver.pages(it, context.chapters, context.pageCount, context.reading.currentPage)
        }
        Log.d(TAG, "$candidate → $location → $pages")
        if (location == null || pages == null) return null
        return LocatedQuestion(
            location,
            LocationResolver.limit(pages, context.reading.spoilerLimit),
            LocationRules.strip(question, candidate.span)
        )
    }

    /**
     * El hecho, entre los de los capítulos que ya tienen hechos. Con anti-spoilers, solo los leídos
     * enteros: los hechos de un capítulo cuentan el capítulo completo.
     */
    private suspend fun event(
        bookId: String,
        question: String,
        expression: String,
        context: LocationContext
    ): BookLocation? {
        val limit = context.reading.spoilerLimit
        val saved = savedEvents(bookId)
        val known = context.chapters
            .filter { limit == null || it.endPage <= limit }
            .mapNotNull { chapter ->
                val events = saved[chapter.id]?.let(EventLines::parse).orEmpty()
                ChapterEvents(chapter, events).takeIf { events.isNotEmpty() }
            }
        if (known.isEmpty()) return null
        val anchor = judge.locateEvent(question, expression, known) ?: return null
        return BookLocation.AroundEvent(anchor.chapterId, anchor.relation)
    }

    private companion object {
        const val TAG = "BookLocator"
    }
}
