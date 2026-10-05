package dev.joseramos.aireader.ai.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Qué encuentran las reglas en una pregunta: ubicaciones seguras (sin preguntar al LLM), dudosas
 * (las decide el LLM), referencias a un hecho y expresiones que no sitúan nada en el libro.
 */
class BookLocationRulesTest {
    private fun clear(question: String): BookLocation? =
        (LocationRules.detect(question, literature = true) as? LocationCandidate.Clear)?.location

    private fun doubtful(question: String): LocationCandidate.Doubtful? =
        LocationRules.detect(question, literature = true) as? LocationCandidate.Doubtful

    private fun event(question: String, literature: Boolean = true): String? =
        (LocationRules.detect(question, literature) as? LocationCandidate.Event)?.expression

    private fun chapters(vararg refs: ChapterRef, stretch: Stretch = Stretch.WHOLE) =
        BookLocation.Chapters(refs.toList(), stretch)

    private fun numbers(vararg numbers: Int) = BookLocation.Chapters(numbers.map { ChapterRef.Number(it) })

    @Test
    fun chaptersAndPartsAreClear() {
        assertEquals(numbers(2), clear("¿Qué hace Raskólnikov durante el capítulo 2?"))
        assertEquals(numbers(2), clear("que hace raskolnikov durante el capitulo 2"))
        assertEquals(numbers(4), clear("¿Qué pasa en el capítulo IV?"))
        assertEquals(numbers(5), clear("Resume el capítulo cinco"))
        assertEquals(numbers(1), clear("¿Cómo empieza el primer capítulo?"))
        assertEquals(numbers(3, 4), clear("¿Quién aparece en los capítulos 3 y 4?"))
        assertEquals(numbers(3, 4, 5), clear("Resume del capítulo 3 al 5"))
        assertEquals(numbers(3, 4, 5), clear("¿Qué pasa en los capítulos 3-5?"))
        assertEquals(chapters(ChapterRef.Last), clear("¿Cómo termina el último capítulo?"))
        assertEquals(BookLocation.Part(2), clear("¿Qué ocurre en la segunda parte?"))
        assertEquals(BookLocation.Part(3), clear("Resume la parte III"))
        assertEquals(chapters(ChapterRef.Titled("epilogo")), clear("¿Qué cuenta el epílogo?"))
        assertEquals(chapters(ChapterRef.Number(3, part = 2)), clear("¿Qué pasa en el capítulo 3 de la segunda parte?"))
    }

    @Test
    fun theChapterBeingReadAndItsNeighbours() {
        assertEquals(chapters(ChapterRef.Current), clear("Resume este capítulo"))
        assertEquals(chapters(ChapterRef.Current), clear("¿De qué trata el tema actual?"))
        assertEquals(chapters(ChapterRef.Current), clear("Cuéntame el capítulo en el que estoy"))
        assertEquals(chapters(ChapterRef.Previous), clear("Resúmeme el capítulo anterior"))
        assertEquals(chapters(ChapterRef.Previous), clear("¿Qué pasó en el anterior capítulo?"))
        assertEquals(chapters(ChapterRef.Next), clear("¿De qué trata el capítulo siguiente?"))
    }

    @Test
    fun aStretchOfAChapterOrPart() {
        assertEquals(chapters(ChapterRef.Number(3), stretch = Stretch.END), clear("¿Qué pasa al final del capítulo 3?"))
        assertEquals(
            chapters(ChapterRef.Current, stretch = Stretch.START),
            clear("¿Qué pasa al principio de este capítulo?")
        )
        assertEquals(BookLocation.Part(2, Stretch.MIDDLE), clear("¿Qué pasa a mitad de la segunda parte?"))
        assertEquals(
            chapters(ChapterRef.Titled("epilogo"), stretch = Stretch.END),
            clear("¿Qué pasa en las últimas páginas del epílogo?")
        )
        // Sin número es el capítulo que se está leyendo.
        assertEquals(chapters(ChapterRef.Current, stretch = Stretch.END), clear("¿Qué pasa al final del capítulo?"))
    }

    @Test
    fun aStretchOfTheBookIsClearWhenItSaysOfWhat() {
        assertEquals(BookLocation.Book(Stretch.END), clear("¿Qué hace Raskólnikov al final del libro?"))
        assertEquals(BookLocation.Book(Stretch.END), clear("que hace raskolnikov al final del libro"))
        assertEquals(BookLocation.Book(Stretch.START), clear("¿Dónde vive al principio de la novela?"))
        assertEquals(BookLocation.Book(Stretch.MIDDLE), clear("¿Qué ocurre a mitad del libro?"))
        assertEquals(BookLocation.Book(Stretch.END), clear("¿Qué siente acabando el libro?"))
        assertEquals(BookLocation.Book(Stretch.START), clear("¿Quién aparece cuando empieza la novela?"))
        assertEquals(BookLocation.Book(Stretch.END), clear("¿Qué pasa en las últimas páginas?"))
        assertEquals(BookLocation.Book(Stretch.END), clear("¿Qué pasa en los últimos capítulos?"))
        assertEquals(BookLocation.Book(Stretch.START), clear("¿Quiénes salen en los primeros capítulos?"))
        assertEquals(BookLocation.Book(Stretch.END), clear("¿Cuál es el desenlace?"))
    }

    /** Los casos dudosos: lo decide el LLM, con la expresión tal como está en la pregunta. */
    @Test
    fun doubtfulExpressionsGoToTheJudge() {
        val cases = mapOf(
            "¿Qué tiene Raskólnikov al principio de su mano?" to "al principio de su mano",
            "¿Qué hay al final de la calle?" to "al final de la calle",
            "¿Qué hace con la mitad del dinero?" to "la mitad del dinero",
            "¿Qué piensa al principio de su vida en San Petersburgo?" to "al principio de su vida",
            "¿Qué dice al principio de la carta de su madre?" to "al principio de la carta",
            "¿Qué hace Raskólnikov al final?" to "al final",
            "¿Cómo está Sonia al inicio?" to "al inicio",
            "¿Cómo está Sonia a la mitad?" to "a la mitad",
            "¿Qué pasa en el último capítulo de su vida?" to "en el último capítulo de su vida",
            "¿Qué dice en la segunda parte de su artículo?" to "en la segunda parte de su artículo",
            "¿Qué pasa al final de la historia?" to "al final de la historia"
        )
        for ((question, expression) in cases) assertEquals(question, expression, doubtful(question)?.expression)

        val hand = doubtful("¿Qué tiene Raskólnikov al principio de su mano?")
        assertEquals(BookLocation.Book(Stretch.START), hand?.location)
        assertEquals(chapters(ChapterRef.Last), doubtful("¿Qué pasa en el último capítulo de su vida?")?.location)
    }

    @Test
    fun idiomsAreNeverALocation() {
        listOf(
            "¿Quién mató a la vieja usurera?",
            "¿Por fin confiesa Raskólnikov?",
            "¿Qué hace el fin de semana?",
            "En fin, ¿qué opina Razumijin de él?",
            "¿Lo hace con el fin de ayudar a su familia?",
            "¿Qué cobra a mediados de mes?",
            "¿Se comunican por medio de cartas?",
            "Después de todo, ¿es culpable?",
            "Antes que nada, ¿quién es Luzhin?",
            "¿Lee el libro de principio a fin?",
            "En principio, ¿quién es el culpable?",
            "Resume el libro"
        ).forEach { assertNull(it, LocationRules.detect(it, literature = true)) }
    }

    @Test
    fun eventsOnlyInNovels() {
        assertEquals("después del crimen", event("¿Qué hace Raskólnikov después del crimen?"))
        assertEquals("antes de conocer a Sonia", event("¿Qué pensaba antes de conocer a Sonia?"))
        assertEquals("tras la muerte de Marmeladov", event("¿Qué hace tras la muerte de Marmeladov?"))
        assertEquals("durante el entierro", event("¿Quién llora durante el entierro?"))
        assertNull(event("¿Qué hace Raskólnikov después del crimen?", literature = false))
        // Remiten a la conversación o son un rato cualquiera.
        assertNull(LocationRules.detect("¿Y después de eso?", literature = true))
        assertNull(LocationRules.detect("¿Qué hace durante el día?", literature = true))
        // Una ubicación estructural gana a la del hecho.
        assertEquals(numbers(2), clear("¿Qué hace después del crimen en el capítulo 2?"))
    }

    @Test
    fun theLocationIsLeftOutOfTheSearch() {
        fun stripped(question: String) = LocationRules.strip(question, LocationRules.detect(question, true)!!.span)

        assertEquals("¿Qué hace Raskólnikov?", stripped("¿Qué hace Raskólnikov al final del libro?"))
        assertEquals("¿Qué hace Raskólnikov?", stripped("¿Qué hace Raskólnikov durante el capítulo 2?"))
        assertEquals("¿qué hace Sonia?", stripped("Al final del libro, ¿qué hace Sonia?"))
        assertEquals("¿Qué hace Raskólnikov?", stripped("¿Qué hace Raskólnikov después del crimen?"))
        // Si no queda nada que buscar, se busca con la pregunta entera.
        assertEquals("¿El epílogo?", stripped("¿El epílogo?"))
    }

    @Test
    fun summaryRequestsAreToldApartFromQuestions() {
        assertTrue(QueryRouter.isSummary("Hazme un resumen del capítulo 3"))
        assertTrue(QueryRouter.isSummary("¿Qué pasó en el capítulo anterior?"))
        assertFalse(QueryRouter.isSummary("¿Quién mata al prestamista en el capítulo 3?"))
        assertEquals(numbers(3), clear("¿Quién mata al prestamista en el capítulo 3?"))
    }

    @Test
    fun numbersAndPartTitles() {
        assertEquals(4, LocationText.number("iv"))
        assertEquals(5, LocationText.number("quinta"))
        assertEquals(12, LocationText.number("doce"))
        assertNull(LocationText.number("capitulos"))
        assertEquals(2, LocationText.partNumber("PARTE 2"))
        assertEquals(2, LocationText.partNumber("Parte segunda"))
        assertEquals(1, LocationText.partNumber("Primera parte"))
        assertEquals(1, LocationText.partNumber("PARTE 1. CAPÍTULO 1"))
        assertNull(LocationText.partNumber("CAPÍTULO 2"))
    }

    @Test
    fun eventLinesKeepOnlyTheBullets() {
        assertEquals(
            listOf("Raskólnikov mata a la usurera.", "Huye sin que lo vean."),
            EventLines.parse("- Raskólnikov mata a la usurera.\n• Huye sin que lo vean.\n\nNada más.")
        )
        assertEquals(listOf("Uno", "Dos"), EventLines.parse("1. Uno\n2) Dos"))
        assertTrue(EventLines.parse("El modelo no ha querido enumerar los hechos de este capítulo.").isEmpty())
    }
}
