package dev.joseramos.aireader.indexing

import dev.joseramos.aireader.core.data.db.DocumentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentClassifierTest {
    private fun pages(vararg paragraphs: String, copies: Int = 10) = List(copies) { paragraphs.toList() }

    @Test
    fun novelWithDialogueIsLiterature() {
        val novel = pages(
            "A principios de julio, en una tarde extraordinariamente calurosa, un joven salió de su cuartucho.",
            "—¿Adónde vas tan temprano? —preguntó la patrona desde la escalera.",
            "—A ninguna parte —respondió él sin volverse.",
            "Bajó despacio, procurando no hacer ruido, y salió a la calle."
        )
        val result = DocumentClassifier.classify(
            novel,
            pageCount = 300,
            chapterTitles = listOf("Capítulo I", "Capítulo II")
        )
        assertEquals(DocumentType.LITERATURE, result.type)
        assertTrue(result.conclusive)
    }

    @Test
    fun paperWithSectionsAndCitationsIsScientific() {
        val paper = listOf(
            listOf(
                "Resumen",
                "Estudiamos la consolidación de la memoria durante el sueño.",
                "Palabras clave: sueño, memoria"
            ),
            listOf(
                "1. Introducción",
                "Trabajos previos (García, 2019) y (Smith et al., 2021) muestran efectos [3, 4]."
            ),
            listOf("2. Métodos", "Participaron 40 voluntarios [5]. Ver doi: 10.1000/xyz123 (Pérez, 2018)."),
            listOf("3. Resultados", "El grupo experimental mejoró (López y Ruiz, 2020) [6] [7] [8] [9]."),
            listOf("4. Discusión", "Coincide con (Martín, 2017), (Sanz, 2016), (Gil, 2015) y (Roca, 2014) [10] [11]."),
            listOf("Referencias", "[1] García, A. (2019). Memoria. [2] Smith, B. (2021). Sleep.")
        )
        val result = DocumentClassifier.classify(paper, pageCount = 12, chapterTitles = emptyList())
        assertEquals(DocumentType.SCIENTIFIC, result.type)
        assertTrue(result.conclusive)
    }

    @Test
    fun textbookWithUnitsAndExercisesIsEducational() {
        val textbook = List(6) { unit ->
            listOf(
                "Tema ${unit + 1}",
                "Objetivos de aprendizaje",
                "La fotosíntesis transforma la energía de la luz en energía química.",
                "Ejercicios",
                "1. ¿Qué orgánulo realiza la fotosíntesis?",
                "2. ¿Qué gas se libera durante el proceso?"
            )
        }
        val result = DocumentClassifier.classify(
            textbook,
            pageCount = 120,
            chapterTitles = listOf("Unidad 1", "Unidad 2")
        )
        assertEquals(DocumentType.EDUCATIONAL, result.type)
        assertTrue(result.conclusive)
    }

    @Test
    fun shortOrSignalFreeDocumentsAreGeneric() {
        val invoice = pages("Factura n.º 2024-118", "Importe total: 145,20 €", copies = 2)
        assertEquals(DocumentType.GENERIC, DocumentClassifier.classify(invoice, 2, emptyList()).type)

        val memo = pages("El horario de la oficina cambia a partir del lunes.", "Las reuniones serán los martes.")
        val result = DocumentClassifier.classify(memo, pageCount = 20, chapterTitles = emptyList())
        assertEquals(DocumentType.GENERIC, result.type)
        assertFalse(result.conclusive)
    }
}
