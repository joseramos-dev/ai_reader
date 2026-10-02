package dev.joseramos.aireader.ai.characters

import dev.joseramos.aireader.core.data.book.BookContentRepository
import dev.joseramos.aireader.core.data.book.CharacterRepository
import dev.joseramos.aireader.core.data.book.ReadingPositionRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn

/** Personajes de un libro filtrados sin spoilers, actualizados según se lee y se analiza. */
class CharacterBrowser @Inject constructor(
    private val characters: CharacterRepository,
    private val positions: ReadingPositionRepository,
    private val content: BookContentRepository
) {
    /**
     * @param pageLimit límite adicional (por ejemplo, «hasta el capítulo N» del grafo); nunca permite
     * ver más allá de la página más avanzada leída.
     */
    fun observe(bookId: String, pageLimit: Flow<Int?> = flowOf(null)): Flow<CharactersSnapshot> = combine(
        characters.observe(bookId),
        positions.observeMaxPage(bookId).distinctUntilChanged(),
        content.observePages(bookId),
        pageLimit
    ) { data, maxPage, pages, limit ->
        SpoilerFilter.snapshot(data, limit?.coerceAtMost(maxPage) ?: maxPage, pages)
    }.flowOn(Dispatchers.Default)
}
