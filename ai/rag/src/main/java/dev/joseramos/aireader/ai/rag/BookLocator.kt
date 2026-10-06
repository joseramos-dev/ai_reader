package dev.joseramos.aireader.ai.rag

import android.util.Log
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.ChapterKeyPoints
import dev.joseramos.aireader.core.data.book.KeyPointsRepository
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
 * hechos clave de cada capítulo, y el [LocationResolver] lo convierte en páginas.
 */
class BookLocator internal constructor(
    private val judge: LocationJudge,
    /** Hechos clave guardados de cada capítulo del libro, por id de capítulo. */
    private val savedEvents: suspend (bookId: String) -> Map<Long, ChapterKeyPoints>
) {
    @Inject constructor(judge: LocationJudge, keyPoints: KeyPointsRepository) : this(judge, keyPoints::all)

    /**
     * Dónde se sitúa [question], o `null` si no habla de ninguna parte del libro (o no se ha podido situar).
     * Si la pregunta sitúa algo respecto a un hecho, antes se llama a [prepareEvents] con los capítulos en
     * los que buscarlo, para que genere los hechos clave que les falten.
     */
    suspend fun locate(
        bookId: String,
        question: String,
        context: LocationContext,
        prepareEvents: suspend (List<Chapter>) -> Unit = {}
    ): LocatedQuestion? {
        val candidate = LocationRules.detect(question, context.literature) ?: return null
        val location = when (candidate) {
            is LocationCandidate.Clear -> candidate.location
            is LocationCandidate.Doubtful ->
                candidate.location.takeIf { judge.refersToBook(question, candidate.expression) == true }
            is LocationCandidate.Event -> event(bookId, question, candidate.expression, context, prepareEvents)
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
     * El hecho, entre los de los capítulos que tienen hechos clave. Lo más probable es que pregunte por
     * algo ya leído, así que antes se preparan los de todos los capítulos hasta el que se está leyendo
     * (con anti-spoilers, hasta la página más avanzada leída), el actual incluido. Con anti-spoilers solo
     * cuentan los hechos que ocurren hasta esa página.
     */
    private suspend fun event(
        bookId: String,
        question: String,
        expression: String,
        context: LocationContext,
        prepareEvents: suspend (List<Chapter>) -> Unit
    ): BookLocation? {
        val limit = context.reading.spoilerLimit
        val reached = limit ?: context.reading.currentPage
        prepareEvents(context.chapters.filter { it.startPage <= reached })
        val saved = savedEvents(bookId)
        val known = context.chapters
            .filter { limit == null || it.startPage <= limit }
            .mapNotNull { chapter ->
                val events = saved[chapter.id]?.until(limit).orEmpty().map { it.text }
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
