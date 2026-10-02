package dev.joseramos.aireader.tts

import dev.joseramos.aireader.core.data.book.PageText
import dev.joseramos.aireader.text.PhraseSplitter

/**
 * Saltos de página de la voz, sin acceso a datos: decide a qué página ir a partir de las páginas
 * de texto que se le pasan (pueden faltar páginas o venir desordenadas).
 */
internal object PageNavigation {

    /** Una página se puede leer si tiene alguna frase y no es una página escaneada (imagen sin texto). */
    fun isReadable(page: PageText): Boolean =
        !page.isScanned && page.paragraphs.any { PhraseSplitter.split(it).isNotEmpty() }

    /** Primera página legible después de [current], o `null` si [current] es la última con texto. */
    fun nextReadable(pages: List<PageText>, current: Int): Int? =
        pages.filter { it.page > current && isReadable(it) }.minOfOrNull { it.page }

    /** Última página legible antes de [current], o `null` si no hay ninguna. */
    fun previousReadable(pages: List<PageText>, current: Int): Int? =
        pages.filter { it.page < current && isReadable(it) }.maxOfOrNull { it.page }
}
