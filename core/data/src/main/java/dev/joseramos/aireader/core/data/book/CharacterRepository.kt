package dev.joseramos.aireader.core.data.book

import dev.joseramos.aireader.core.data.db.CharacterDao
import dev.joseramos.aireader.core.data.db.NameKind
import dev.joseramos.aireader.core.data.db.RelationType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Nombre o apodo de un personaje y la página donde aparece por primera vez. */
data class CharacterName(val name: String, val kind: NameKind, val firstPage: Int, val primaryFrom: Int? = null)

/** Algo que el libro cuenta de un personaje, en una página concreta. */
data class CharacterFact(val page: Int, val text: String)

/** Personaje con todo lo extraído hasta ahora, sin filtrar: las reglas sin spoilers van aparte. */
data class BookCharacter(
    val id: Long,
    val firstPage: Int,
    val names: List<CharacterName>,
    val facts: List<CharacterFact> = emptyList()
)

data class CharacterRelation(
    val id: Long,
    val fromId: Long,
    val toId: Long,
    val type: RelationType,
    val label: String,
    val page: Int,
    val endsAtPage: Int? = null
)

/** Todo lo que se sabe de los personajes de un libro y qué capítulos se han analizado ya. */
data class CharacterData(
    val characters: List<BookCharacter> = emptyList(),
    val relations: List<CharacterRelation> = emptyList(),
    val scannedChapterIds: Set<Long> = emptySet()
)

@Singleton
class CharacterRepository @Inject constructor(private val dao: CharacterDao) {
    fun observe(bookId: String): Flow<CharacterData> = combine(
        dao.observeCharacters(bookId),
        dao.observeNames(bookId),
        dao.observeFacts(bookId),
        dao.observeRelations(bookId),
        dao.observeScans(bookId)
    ) { characters, names, facts, relations, scans ->
        val namesByCharacter = names.groupBy { it.characterId }
        val factsByCharacter = facts.groupBy { it.characterId }
        CharacterData(
            characters = characters.map { character ->
                BookCharacter(
                    id = character.id,
                    firstPage = character.firstPage,
                    names = namesByCharacter[character.id].orEmpty().map {
                        CharacterName(it.name, it.kind, it.firstPage, it.isPrimaryFrom)
                    },
                    facts = factsByCharacter[character.id].orEmpty().map { CharacterFact(it.page, it.text) }
                )
            },
            relations = relations.map {
                CharacterRelation(it.id, it.fromId, it.toId, it.type, it.label, it.page, it.endsAtPage)
            },
            scannedChapterIds = scans.mapTo(mutableSetOf()) { it.chapterId }
        )
    }
}
