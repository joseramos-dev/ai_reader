package dev.joseramos.aireader.tts

import dev.joseramos.aireader.core.data.book.BookContentRepository
import dev.joseramos.aireader.core.data.book.PageText
import dev.joseramos.aireader.core.data.book.ReadingPosition
import dev.joseramos.aireader.text.PhraseSplitter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Frase que se va a leer, con su posición exacta en el libro. */
data class Phrase(val position: ReadingPosition, val text: String)

/**
 * Recorre el texto limpio del libro frase a frase desde una posición. Las frases se calculan con
 * [PhraseSplitter], igual que el resaltado del modo texto, para que los índices coincidan.
 */
class PhraseSource(private val content: BookContentRepository, private val bookId: String) {

    fun from(start: ReadingPosition): Flow<Phrase> = flow {
        var page = start.page
        while (true) {
            val batch = content.pagesFrom(bookId, page, BATCH_PAGES)
            if (batch.isEmpty()) break
            for (pageText in batch) {
                for (phrase in phrasesOf(pageText)) {
                    if (phrase.position.isBefore(start)) continue
                    emit(phrase)
                }
            }
            page = batch.last().page + 1
        }
    }

    /** Posición de la frase anterior a [position], o la misma si es la primera del libro. */
    suspend fun previous(position: ReadingPosition): ReadingPosition {
        val window = content.pagesFrom(bookId, (position.page - LOOKBACK_PAGES).coerceAtLeast(1), LOOKBACK_PAGES + 1)
        return window.flatMap(::phrasesOf).map { it.position }.lastOrNull { it.isBefore(position) } ?: position
    }

    /** Posición de la frase siguiente a [position], o `null` si es la última del libro. */
    suspend fun next(position: ReadingPosition): ReadingPosition? {
        var page = position.page
        while (true) {
            val batch = content.pagesFrom(bookId, page, BATCH_PAGES)
            if (batch.isEmpty()) return null
            batch.flatMap(::phrasesOf).firstOrNull { position.isBefore(it.position) }?.let { return it.position }
            page = batch.last().page + 1
        }
    }

    /** Principio de la siguiente página con texto después de [page], o `null` si no queda ninguna. */
    suspend fun nextPage(page: Int): ReadingPosition? {
        var from = page + 1
        while (true) {
            val batch = content.pagesFrom(bookId, from, SCAN_PAGES)
            if (batch.isEmpty()) return null
            PageNavigation.nextReadable(batch, page)?.let { return ReadingPosition(it) }
            from = batch.last().page + 1
        }
    }

    /** Principio de la página con texto anterior a [page], o `null` si no hay ninguna. */
    suspend fun previousPage(page: Int): ReadingPosition? {
        var end = page
        while (end > 1) {
            val start = (end - SCAN_PAGES).coerceAtLeast(1)
            val window = content.pagesFrom(bookId, start, end - start)
            PageNavigation.previousReadable(window, page)?.let { return ReadingPosition(it) }
            end = start
        }
        return null
    }

    private fun phrasesOf(page: PageText): List<Phrase> = page.paragraphs.flatMapIndexed { p, paragraph ->
        PhraseSplitter.split(paragraph).mapIndexed { f, text -> Phrase(ReadingPosition(page.page, p, f), text) }
    }

    private companion object {
        const val BATCH_PAGES = 5
        const val LOOKBACK_PAGES = 3

        /** Páginas que se miran de una vez al buscar la siguiente o la anterior con texto. */
        const val SCAN_PAGES = 20
    }
}

internal fun ReadingPosition.isBefore(other: ReadingPosition): Boolean = when {
    page != other.page -> page < other.page
    paragraph != other.paragraph -> paragraph < other.paragraph
    else -> phrase < other.phrase
}
