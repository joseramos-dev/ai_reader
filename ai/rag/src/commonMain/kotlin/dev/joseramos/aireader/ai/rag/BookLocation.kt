package dev.joseramos.aireader.ai.rag

import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.text.SpeechNormalizer
import java.text.Normalizer
import kotlin.math.ceil

/** Tramo de un libro, de un capítulo o de una parte: entero, el principio, la mitad o el final. */
enum class Stretch { WHOLE, START, MIDDLE, END }

/** Si se pregunta por lo que pasa antes, durante o después de un hecho de la historia. */
enum class EventRelation { BEFORE, DURING, AFTER }

/** Parte del libro a la que se refiere una pregunta. */
sealed interface BookLocation {
    /** Uno o varios capítulos («el capítulo 2», «los capítulos 3 y 4», «al final del capítulo 3»). */
    data class Chapters(val refs: List<ChapterRef>, val stretch: Stretch = Stretch.WHOLE) : BookLocation

    /** Una parte de un libro dividido en partes («la segunda parte»). */
    data class Part(val number: Int, val stretch: Stretch = Stretch.WHOLE) : BookLocation

    /** Un tramo del libro entero («al final del libro», «al principio»). */
    data class Book(val stretch: Stretch) : BookLocation

    /** Alrededor del capítulo [chapterId], donde ocurre un hecho («después del crimen»). */
    data class AroundEvent(val chapterId: Long, val relation: EventRelation) : BookLocation

    /** La misma ubicación, limitada a un tramo («al final del capítulo 3»). */
    fun within(stretch: Stretch): BookLocation = when (this) {
        is Chapters -> copy(stretch = stretch)
        is Part -> copy(stretch = stretch)
        is Book -> copy(stretch = stretch)
        is AroundEvent -> this
    }
}

/**
 * Lo que encuentran las reglas en una pregunta. [span] es lo que ocupa la expresión en la pregunta
 * (normalizada a NFC), para quitarla de la búsqueda si se acepta.
 */
sealed interface LocationCandidate {
    val span: IntRange

    /** Ubicación segura, sin preguntar a nadie («el capítulo 2», «al final del libro»). */
    data class Clear(val location: BookLocation, override val span: IntRange) : LocationCandidate

    /** Puede ser una parte del libro o no («al final», «al principio de su mano»): lo decide el LLM. */
    data class Doubtful(val location: BookLocation, override val span: IntRange, val expression: String) :
        LocationCandidate

    /** Se sitúa respecto a un hecho («después del crimen»): el LLM lo busca entre los hechos de cada capítulo. */
    data class Event(override val span: IntRange, val expression: String) : LocationCandidate
}

/**
 * Texto de las preguntas y de los títulos tal como lo leen las reglas: en minúsculas y sin tildes,
 * carácter a carácter (para que las posiciones coincidan con las del original), y los números en
 * cifras, romanos, palabras («cinco») u ordinales («quinta»).
 */
internal object LocationText {
    private val WORD_NUMBERS = listOf(
        "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez", "once", "doce", "trece",
        "catorce", "quince", "dieciseis", "diecisiete", "dieciocho", "diecinueve", "veinte"
    )
    private val ORDINALS = mapOf(
        "primer" to 1, "primero" to 1, "primera" to 1, "segundo" to 2, "segunda" to 2, "tercer" to 3,
        "tercero" to 3, "tercera" to 3, "cuarto" to 4, "cuarta" to 4, "quinto" to 5, "quinta" to 5, "sexto" to 6,
        "sexta" to 6, "septimo" to 7, "septima" to 7, "setimo" to 7, "setima" to 7, "octavo" to 8, "octava" to 8,
        "noveno" to 9, "novena" to 9, "decimo" to 10, "decima" to 10
    )
    private val WORDS = WORD_NUMBERS.joinToString("|")
    private val ORDS = ORDINALS.keys.sortedByDescending { it.length }.joinToString("|")

    /** Un número (grupo de captura). */
    val NUM = """(\d{1,3}|$WORDS|[ivxlc]{1,7})"""

    /** Un ordinal (grupo de captura). */
    val ORD = """($ORDS)"""

    /** Un número o un ordinal (grupo de captura). */
    val NUM_OR_ORD = """(\d{1,3}|$WORDS|$ORDS|[ivxlc]{1,7})"""

    private val ROMAN = Regex("[ivxlc]+")
    private val PART_TITLE = Regex("""^\s*(?:parte|libro|part|book)\s+$NUM_OR_ORD\b|^\s*$ORD\s+(?:parte|libro)\b""")
    private val FOLDED = mapOf(
        'á' to 'a', 'é' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u', 'ü' to 'u',
        'à' to 'a', 'è' to 'e', 'ì' to 'i', 'ò' to 'o', 'ù' to 'u'
    )

    fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

    /** Minúsculas y sin tildes, carácter a carácter: las posiciones no cambian. */
    fun fold(text: String): String = buildString(text.length) {
        for (c in text) {
            val lower = c.lowercaseChar()
            append(FOLDED[lower] ?: lower)
        }
    }

    fun number(token: String): Int? = token.toIntOrNull()
        ?: WORD_NUMBERS.indexOf(token).takeIf { it >= 0 }?.plus(1)
        ?: ORDINALS[token]
        ?: token.takeIf { ROMAN.matches(it) }?.let { SpeechNormalizer.romanToInt(it.uppercase()) }

    /** Número de una parte por su título («PARTE 2», «Parte segunda», «Primera parte»), o `null`. */
    fun partNumber(title: String): Int? {
        val match = PART_TITLE.find(fold(nfc(title))) ?: return null
        return number(match.groupValues[1].ifEmpty { match.groupValues[2] })
    }
}

/**
 * Reglas que encuentran en una pregunta a qué parte del libro se refiere:
 * - Estructural: capítulos («el capítulo 2», «del 3 al 5», «el último capítulo», «el anterior»),
 *   partes («la segunda parte») y epílogo o prólogo. Son seguras, salvo que sean de otra cosa
 *   («el último capítulo de su vida»).
 * - Relativa: principio, mitad o final. Es segura si dice de qué («al final del libro», «en las
 *   últimas páginas»); si no lo dice o es de otra cosa («al final», «al principio de su mano»), es dudosa.
 * - Por evento («después del crimen»): solo en novelas y si no hay otra ubicación.
 * Las expresiones hechas («por fin», «fin de semana», «después de todo») nunca son una ubicación.
 */
object LocationRules {
    private val NUM = LocationText.NUM
    private val ORD = LocationText.ORD
    private val NUM_OR_ORD = LocationText.NUM_OR_ORD
    private const val CH = """(?:capitulo|cap\.?|tema)"""
    private const val CHS = """(?:capitulos|caps\.?|temas)"""
    private val TOKEN = Regex("""[\p{L}\d]+""")
    private const val MAX_CHAPTER_SPAN = 60
    private const val MIN_EVENT_WORD = 3

    /** Lo único que puede seguir a una ubicación para que sea segura: «del libro», «de la novela». */
    private val BOOK_NOUNS = setOf("libro", "novela", "obra")
    private val CHAPTER_NOUNS = setOf("capitulo", "tema")

    private class Rule(val regex: Regex, val build: (MatchResult) -> BookLocation?)

    private class Trigger(val regex: Regex, val stretch: Stretch, val bareIsClear: Boolean)

    /** Las estructurales, de la más concreta a la más general: gana la primera que encaja. */
    private val STRUCTURAL = listOf(
        Rule(Regex("""\b(?:del\s+)?$CH\s+$NUM\s+(?:al|hasta\s+el)\s+(?:$CH\s+)?$NUM\b""")) {
            span(it.groupValues[1], it.groupValues[2])
        },
        Rule(Regex("""\b(?:los\s+)?$CHS\s+(?:del?\s+)?$NUM\s*(?:al|a|hasta\s+el|-|–)\s*$NUM\b""")) {
            span(it.groupValues[1], it.groupValues[2])
        },
        Rule(Regex("""\b(?:los\s+)?$CHS\s+$NUM(?:\s*,\s*$NUM)*\s+[ye]\s+$NUM\b""")) { match ->
            val numbers = TOKEN.findAll(match.value).mapNotNull { LocationText.number(it.value) }.distinct()
            BookLocation.Chapters(numbers.map { ChapterRef.Number(it) }.toList())
        },
        Rule(Regex("""\b$CH\s+$NUM_OR_ORD\s+de\s+la\s+(?:parte\s+$NUM_OR_ORD|$ORD\s+parte)\b""")) {
            partChapter(it.groupValues[1], it.groupValues[2].ifEmpty { it.groupValues[3] })
        },
        Rule(Regex("""\b$ORD\s+$CH\s+de\s+la\s+(?:parte\s+$NUM_OR_ORD|$ORD\s+parte)\b""")) {
            partChapter(it.groupValues[1], it.groupValues[2].ifEmpty { it.groupValues[3] })
        },
        Rule(Regex("""\b(?:ultimo|final)\s+$CH\b|\b$CH\s+(?:final|ultimo)\b""")) {
            BookLocation.Chapters(listOf(ChapterRef.Last))
        },
        Rule(Regex("""\b$CH\s+(?:anterior|pasado|previo)\b|\banterior\s+$CH\b""")) {
            BookLocation.Chapters(listOf(ChapterRef.Previous))
        },
        Rule(Regex("""\b$CH\s+(?:siguiente|proximo)\b|\b(?:siguiente|proximo)\s+$CH\b""")) {
            BookLocation.Chapters(listOf(ChapterRef.Next))
        },
        Rule(
            Regex(
                """\b(?:este|esta|el\s+actual|actual)\s*(?:capitulo|tema|parte)\b|\b$CH\s+actual\b|""" +
                    """\bcapitulo\s+(?:en\s+el\s+)?que\s+(?:estoy|leo)\b"""
            )
        ) { BookLocation.Chapters(listOf(ChapterRef.Current)) },
        Rule(Regex("""\b$CH\s+$NUM_OR_ORD\b|\b$ORD\s+$CH\b""")) { match ->
            LocationText.number(match.groupValues[1].ifEmpty { match.groupValues[2] })
                ?.let { BookLocation.Chapters(listOf(ChapterRef.Number(it))) }
        },
        Rule(Regex("""\bparte\s+$NUM_OR_ORD\b|\b$ORD\s+parte\b""")) { match ->
            LocationText.number(match.groupValues[1].ifEmpty { match.groupValues[2] })?.let { BookLocation.Part(it) }
        },
        Rule(Regex("""\b(epilogo|prologo)\b""")) { BookLocation.Chapters(listOf(ChapterRef.Titled(it.groupValues[1]))) }
    )

    /**
     * Principio, mitad y final. [Trigger.bareIsClear]: si sin decir de qué ya es seguro («las últimas
     * páginas», «el desenlace») o dudoso («al final»).
     */
    private val RELATIVE = listOf(
        Trigger(
            Regex(
                """\b(?:(?:al|cuando|nada\s+mas)\s+)?(?:empieza|empiece|empezando|empezar|comienza|comience|""" +
                    """comenzando|comenzar|arranca)\s+(?:el|la|esta|este)\s+(?:libro|novela|obra)\b"""
            ),
            Stretch.START,
            bareIsClear = true
        ),
        Trigger(
            Regex(
                """\b(?:(?:al|cuando|(?:justo\s+)?antes\s+de\s+que)\s+)?(?:acaba|acabe|acabando|acabar|termina|""" +
                    """termine|terminando|terminar|finaliza|finalice|finalizando|finalizar)\s+""" +
                    """(?:el|la|esta|este)\s+(?:libro|novela|obra)\b"""
            ),
            Stretch.END,
            bareIsClear = true
        ),
        Trigger(Regex("""\ba\s+medio\s+(?:libro|novela)\b"""), Stretch.MIDDLE, bareIsClear = true),
        Trigger(Regex("""\b(?:las?\s+)?primeras?\s+paginas?\b"""), Stretch.START, bareIsClear = true),
        Trigger(Regex("""\b(?:los\s+)?primeros\s+$CHS\b"""), Stretch.START, bareIsClear = true),
        Trigger(Regex("""\b(?:las?\s+)?ultimas?\s+paginas?\b"""), Stretch.END, bareIsClear = true),
        Trigger(Regex("""\b(?:los\s+)?ultimos\s+$CHS\b"""), Stretch.END, bareIsClear = true),
        Trigger(Regex("""\b(?:la\s+)?ultima\s+parte\b"""), Stretch.END, bareIsClear = true),
        Trigger(Regex("""\b(?:el\s+)?desenlace\b"""), Stretch.END, bareIsClear = true),
        Trigger(
            Regex("""\b(?:al|el|del|en\s+el|desde\s+el|hacia\s+el|hasta\s+el)\s+(?:principio|inicio|comienzo)\b"""),
            Stretch.START,
            bareIsClear = false
        ),
        Trigger(
            Regex("""\b(?:(?:a|en|hacia|por)\s+)?la\s+mitad\b|\ba\s+mitad\b"""),
            Stretch.MIDDLE,
            bareIsClear = false
        ),
        Trigger(Regex("""\b(?:en|a)\s+medio\b"""), Stretch.MIDDLE, bareIsClear = false),
        Trigger(
            Regex("""\b(?:al|el|del|en\s+el|hacia\s+el|hasta\s+el)\s+(?:final|fin)\b"""),
            Stretch.END,
            bareIsClear = false
        )
    )

    /** Expresiones hechas que nunca sitúan en el libro: se tapan antes de buscar. */
    private val IDIOMS = listOf(
        """\bpor\s+fin\b""",
        """\ben\s+fin\b""",
        """\ba\s+fin\s+de\b""",
        """\bcon\s+(?:el\s+)?fin\s+de\b""",
        """\bfin(?:es)?\s+de\s+semana\b""",
        """\bsin\s+fin\b""",
        """\bal\s+fin(?:al)?\s+y\s+al\s+cabo\b""",
        """\bal\s+fin\b(?!\s+del?\b)""",
        """\bde\s+principio\s+a\s+fin\b""",
        """\ben\s+principio\b""",
        """\bpor\s+principio\b""",
        """\ba\s+medio\s+plazo\b""",
        """\bpor\s+medio\s+de\b""",
        """\bdespues\s+de\s+todo\b""",
        """\bantes\s+(?:que|de)\s+nada\b"""
    ).map(::Regex)

    /** Lo que sitúa respecto a un hecho. «durante» solo con artículo («durante el entierro»). */
    private val EVENT = Regex(
        """\b(?:(?:antes|despues)\s+(?:de\s+que|del|de)|tras|desde\s+que|hasta\s+que|a\s+raiz\s+del?|""" +
            """durante(?=\s+(?:el|la|los|las|su|sus)\s))\s+([^?!.,;:¿¡]+)"""
    )
    private val ARTICLES = setOf("el", "la", "los", "las", "lo", "un", "una", "su", "sus", "que")

    /** Lo que no es un hecho de la historia: remite a la conversación o es un rato cualquiera. */
    private val NOT_EVENTS = setOf(
        "eso", "esto", "ello", "aquello", "todo", "nada", "entonces", "ahora", "hoy", "ayer", "manana", "luego",
        "otro", "otra", "dia", "noche", "tarde", "semana", "mes", "ano", "rato", "tiempo", "momento"
    )

    /**
     * «al final del capítulo 3», «a mitad de la segunda parte», «las últimas páginas del epílogo»:
     * principio, mitad o final justo antes de una ubicación estructural.
     */
    private val STRETCH_PREFIX = Regex(
        """(?:\b(?:al|el|en\s+el|hacia\s+el|del|desde\s+el|hasta\s+el)\s+(principio|inicio|comienzo|final|fin)|""" +
            """(?:\b(?:a|en|hacia)\s+)?(?:\bla\s+)?\b(mitad)|\ben\s+(medio)|""" +
            """(?:\blas?\s+)?\b(ultimas?|primeras?)\s+paginas?)\s+(?:del|de\s+la|de\s+los|de\s+las|de)\s+$"""
    )

    /** Preposición y artículo que acompañan a la ubicación («durante el», «en la»): se quitan con ella. */
    private val LEADING = Regex(
        """(?:\b(?:en|durante|de|del|a|al|hasta|desde|para|sobre|tras|por)\s+)?""" +
            """(?:\b(?:el|la|los|las|este|esta|estos|estas)\s+)?$"""
    )

    /** «de/del» + determinantes + nombre: de qué es el principio, la mitad o el final. */
    private val COMPLEMENT = Regex(
        """^\s*(?:de|del)\s+(?:(?:el|la|los|las|lo|este|esta|estos|estas|ese|esa|esos|esas|aquel|aquella|su|sus|""" +
            """mi|mis|tu|tus|un|una|toda|todo|todas|todos)\s+){0,2}([\p{L}\d]+)"""
    )

    /**
     * Ubicación de [question], o `null` si no habla de ninguna parte del libro. Las referencias a
     * hechos solo se buscan en novelas ([literature]).
     */
    fun detect(question: String, literature: Boolean): LocationCandidate? {
        val text = LocationText.nfc(question)
        var folded = LocationText.fold(text)
        for (idiom in IDIOMS) folded = idiom.replace(folded) { " ".repeat(it.value.length) }
        return structural(text, folded) ?: relative(text, folded) ?: if (literature) event(text, folded) else null
    }

    /** [question] sin lo que ocupa [span] (la expresión que la sitúa), para buscar con el resto. */
    fun strip(question: String, span: IntRange): String {
        val text = LocationText.nfc(question)
        if (span.first < 0 || span.last >= text.length) return question
        val stripped = (text.substring(0, span.first) + " " + text.substring(span.last + 1))
            .replace(Regex("""\s+([?!.,;:])"""), "$1")
            .replace(Regex("""([¿¡])\s+"""), "$1")
            .replace(Regex("""^[\s,;:.]+"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
        return stripped.takeIf { it.any(Char::isLetter) } ?: question
    }

    private fun structural(text: String, folded: String): LocationCandidate? = STRUCTURAL.firstNotNullOfOrNull { rule ->
        val match = rule.regex.find(folded)
        match?.let(rule.build)?.let { structuralCandidate(text, folded, match, it) }
    }

    /** Con el tramo que la precede, si lo hay («al final del capítulo 3»), y la preposición («durante el»). */
    private fun structuralCandidate(
        text: String,
        folded: String,
        match: MatchResult,
        location: BookLocation
    ): LocationCandidate {
        val prefix = STRETCH_PREFIX.find(folded.substring(0, match.range.first))
        val located = prefix?.let { location.within(stretchOf(it)) } ?: location
        val start = leading(folded, prefix?.range?.first ?: match.range.first)
        return complemented(text, folded, start..match.range.last, located, bareIsClear = true)
    }

    /** La expresión relativa que aparece antes en la pregunta. */
    private fun relative(text: String, folded: String): LocationCandidate? {
        val (trigger, match) = RELATIVE.mapNotNull { t -> t.regex.find(folded)?.let { t to it } }
            .minByOrNull { it.second.range.first }
            ?: return null
        return complemented(text, folded, match.range, BookLocation.Book(trigger.stretch), trigger.bareIsClear)
    }

    private fun event(text: String, folded: String): LocationCandidate? {
        val match = EVENT.findAll(folded).firstOrNull { isStoryEvent(it.groupValues[1]) } ?: return null
        val span = match.range.first..(match.range.last - (match.value.length - match.value.trimEnd().length))
        return LocationCandidate.Event(span, text.substring(span.first, span.last + 1))
    }

    /** Si lo que sigue a «después de», «antes de»… puede ser un hecho de la historia. */
    private fun isStoryEvent(phrase: String): Boolean {
        val words = TOKEN.findAll(phrase).map { it.value }.toList()
        val first = words.firstOrNull { it !in ARTICLES } ?: return false
        return first !in NOT_EVENTS && words.any { it.length >= MIN_EVENT_WORD }
    }

    /**
     * Mira de qué es la ubicación: «del libro» la hace segura; «del capítulo» (sin número) es el que
     * se está leyendo; cualquier otra cosa («de su mano») la hace dudosa. Sin complemento, decide [bareIsClear].
     */
    private fun complemented(
        text: String,
        folded: String,
        range: IntRange,
        location: BookLocation,
        bareIsClear: Boolean
    ): LocationCandidate {
        val complement = COMPLEMENT.find(folded.substring(range.last + 1))
        val span = if (complement == null) range else range.first..(range.last + 1 + complement.range.last)
        val noun = complement?.groupValues?.get(1)
        val expression = text.substring(span.first, span.last + 1).trim()
        return when {
            noun == null && bareIsClear -> LocationCandidate.Clear(location, span)
            noun == null -> LocationCandidate.Doubtful(location, span, expression)
            noun in BOOK_NOUNS -> LocationCandidate.Clear(location, span)
            noun in CHAPTER_NOUNS && location is BookLocation.Book ->
                LocationCandidate.Clear(BookLocation.Chapters(listOf(ChapterRef.Current), location.stretch), span)
            else -> LocationCandidate.Doubtful(location, span, expression)
        }
    }

    private fun leading(folded: String, start: Int): Int =
        LEADING.find(folded.substring(0, start))?.range?.first?.takeIf { it < start } ?: start

    private fun stretchOf(prefix: MatchResult): Stretch {
        val bound = prefix.groupValues[1]
        val pages = prefix.groupValues[4]
        return when {
            bound in setOf("principio", "inicio", "comienzo") || pages.startsWith("primera") -> Stretch.START
            bound.isNotEmpty() || pages.isNotEmpty() -> Stretch.END
            else -> Stretch.MIDDLE
        }
    }

    /** «del capítulo 3 al 5»: también los de en medio (como mucho [MAX_CHAPTER_SPAN]). */
    private fun span(from: String, to: String): BookLocation? {
        val a = LocationText.number(from) ?: return null
        val b = LocationText.number(to) ?: return null
        val first = minOf(a, b)
        val last = minOf(maxOf(a, b), first + MAX_CHAPTER_SPAN - 1)
        return BookLocation.Chapters((first..last).map { ChapterRef.Number(it) })
    }

    /** «el capítulo 3 de la segunda parte». */
    private fun partChapter(chapter: String, part: String): BookLocation? {
        val chapterNumber = LocationText.number(chapter) ?: return null
        val partNumber = LocationText.number(part) ?: return null
        return BookLocation.Chapters(listOf(ChapterRef.Number(chapterNumber, partNumber)))
    }
}

/**
 * Convierte una [BookLocation] en rangos de páginas. Los tramos (principio, mitad, final) son una
 * cuarta parte del libro, capítulo o parte; antes o después de un hecho son los dos capítulos
 * anteriores o siguientes al suyo, incluido.
 */
object LocationResolver {
    private const val STRETCH_SHARE = 0.25
    private const val EVENT_WINDOW = 2

    /**
     * Páginas de [location], o `null` si no se puede situar en este libro (un capítulo que no existe,
     * «la segunda parte» en un libro sin partes).
     */
    fun pages(location: BookLocation, chapters: List<Chapter>, pageCount: Int, currentPage: Int): List<IntRange>? =
        when (location) {
            is BookLocation.Book -> if (pageCount < 1) null else listOf(stretch(1..pageCount, location.stretch))
            is BookLocation.Chapters -> chapterPages(location, chapters, currentPage)
            is BookLocation.Part -> partPages(chapters, location.number)?.let { listOf(stretch(it, location.stretch)) }
            is BookLocation.AroundEvent -> eventPages(location, chapters)
        }

    /** Recorta [pages] hasta [limit] (anti-spoilers): lo que queda entero por delante desaparece. */
    fun limit(pages: List<IntRange>, limit: Int?): List<IntRange> = if (limit == null) {
        pages
    } else {
        pages.mapNotNull { (it.first..minOf(it.last, limit)).takeUnless(IntRange::isEmpty) }
    }

    /** El tramo [stretch] de [range]: una cuarta parte al principio, en el centro o al final. */
    internal fun stretch(range: IntRange, stretch: Stretch): IntRange {
        if (range.isEmpty() || stretch == Stretch.WHOLE) return range
        val size = range.last - range.first + 1
        val width = ceil(size * STRETCH_SHARE).toInt().coerceIn(1, size)
        return when (stretch) {
            Stretch.START -> range.first until range.first + width
            Stretch.END -> (range.last - width + 1)..range.last
            else -> (range.first + (size - width) / 2).let { it until it + width }
        }
    }

    /** Páginas de una parte: desde su encabezado hasta el de la siguiente. */
    internal fun partPages(chapters: List<Chapter>, number: Int): IntRange? {
        val headings = chapters.indices.filter { LocationText.partNumber(chapters[it].title) != null }
        val start = headings.firstOrNull { LocationText.partNumber(chapters[it].title) == number } ?: return null
        val end = (headings.firstOrNull { it > start } ?: chapters.size) - 1
        return chapters[start].startPage..chapters[end].endPage
    }

    private fun chapterPages(location: BookLocation.Chapters, chapters: List<Chapter>, page: Int): List<IntRange>? {
        val found = location.refs.mapNotNull { ChapterResolver.resolve(it, chapters, page) }.distinct()
        if (found.isEmpty()) return null
        if (location.stretch != Stretch.WHOLE) {
            return listOf(stretch(found.minOf { it.startPage }..found.maxOf { it.endPage }, location.stretch))
        }
        return merge(found.map { it.startPage..it.endPage })
    }

    private fun eventPages(location: BookLocation.AroundEvent, chapters: List<Chapter>): List<IntRange>? {
        val index = chapters.indexOfFirst { it.id == location.chapterId }.takeIf { it >= 0 } ?: return null
        val relation = location.relation
        val from = if (relation == EventRelation.BEFORE) maxOf(0, index - EVENT_WINDOW) else index
        val to = if (relation == EventRelation.AFTER) minOf(chapters.lastIndex, index + EVENT_WINDOW) else index
        return listOf(chapters[from].startPage..chapters[to].endPage)
    }

    /** Une los rangos que se tocan o se solapan, en orden. */
    private fun merge(ranges: List<IntRange>): List<IntRange> {
        val merged = mutableListOf<IntRange>()
        for (range in ranges.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
        }
        return merged
    }
}
