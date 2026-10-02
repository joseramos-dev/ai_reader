package dev.joseramos.aireader.ai.characters

import dev.joseramos.aireader.core.data.book.CharacterData
import dev.joseramos.aireader.core.data.book.CharacterFact
import dev.joseramos.aireader.core.data.book.CharacterName
import dev.joseramos.aireader.core.data.book.PageText
import dev.joseramos.aireader.core.data.db.NameKind
import dev.joseramos.aireader.core.data.db.RelationType
import dev.joseramos.aireader.text.NameMatcher

/** Personaje tal como se puede mostrar en un punto de la lectura, sin nada de páginas no leídas. */
data class VisibleCharacter(
    val id: Long,
    /** Nombre vigente en el punto de lectura. */
    val name: String,
    /** Los demás nombres y apodos ya aparecidos, con su página. */
    val otherNames: List<CharacterName>,
    val firstPage: Int,
    val facts: List<CharacterFact>,
    /** Veces que se le nombra en las páginas leídas: sirve de medida de importancia. */
    val mentions: Int
)

data class VisibleRelation(
    val id: Long,
    val fromId: Long,
    val toId: Long,
    val type: RelationType,
    val label: String,
    val page: Int,
    /** Página donde terminó, solo si ya se ha leído. */
    val endedAt: Int?
) {
    val isCurrent: Boolean get() = endedAt == null
}

/** Aparición de un personaje en un texto: caracteres `[start, end)`. */
data class NameHit(val start: Int, val end: Int, val characterId: Long)

/** Buscador de los nombres desbloqueados, para resaltarlos y para los chips «En esta página». */
class NameIndex internal constructor(private val matcher: NameMatcher, private val characterIds: LongArray) {
    val isEmpty: Boolean get() = matcher.isEmpty

    fun find(text: String): List<NameHit> = matcher.find(text).map {
        NameHit(it.start, it.end, characterIds[it.pattern])
    }

    /** Personajes nombrados en [texts], en orden de primera aparición. */
    fun charactersIn(texts: List<String>): List<Long> = texts.flatMap { find(it) }.map { it.characterId }.distinct()

    companion object {
        val EMPTY = NameIndex(NameMatcher(emptyList()), LongArray(0))
    }
}

/** Lo que se puede saber de los personajes hasta [maxPage]. */
data class CharactersSnapshot(
    val maxPage: Int = 0,
    val characters: List<VisibleCharacter> = emptyList(),
    val relations: List<VisibleRelation> = emptyList(),
    val index: NameIndex = NameIndex.EMPTY
) {
    private val byId = characters.associateBy { it.id }

    fun character(id: Long): VisibleCharacter? = byId[id]

    fun relationsOf(id: Long): List<VisibleRelation> = relations.filter { it.fromId == id || it.toId == id }
}

/**
 * Reglas sin spoilers (docs/02-diseno-tecnico.md §6.6). Todo se compara con la página más avanzada
 * leída ([maxPage]):
 * - Personaje, nombre o apodo: visible si su primera página ≤ maxPage.
 * - Nombre vigente: el último con `isPrimaryFrom` ≤ maxPage; si no hay, el más nombrado en lo leído.
 * - Dato: visible si su página ≤ maxPage.
 * - Relación: visible si su página ≤ maxPage y se ven los dos personajes; deja de ser vigente si
 *   termina en una página ya leída.
 * Las apariciones se cuentan solo en las páginas leídas y solo con nombres que no comparten dos
 * personajes (un nombre ambiguo no se resalta ni se cuenta).
 */
object SpoilerFilter {
    fun snapshot(data: CharacterData, maxPage: Int, pages: List<PageText>): CharactersSnapshot {
        val visible = data.characters.mapNotNull { character ->
            val names = character.names
                .filter { it.firstPage <= maxPage }
                .groupBy { normalize(it.name) }
                .map { (_, same) ->
                    same.minBy { it.firstPage }.copy(primaryFrom = same.mapNotNull { it.primaryFrom }.minOrNull())
                }
            if (character.firstPage > maxPage || names.isEmpty()) null else character to names
        }

        val owners = visible.flatMap { (character, names) -> names.map { normalize(it.name) to character.id } }
            .groupBy({ it.first }, { it.second })
        val unique = visible.flatMap { (character, names) ->
            names.filter {
                owners[normalize(it.name)]?.distinct()?.size == 1 && it.name.trim().length >= MIN_NAME_LENGTH
            }
                .map { it.name to character.id }
        }
        val index = NameIndex(NameMatcher(unique.map { it.first }), unique.map { it.second }.toLongArray())

        val nameCounts = mutableMapOf<String, Int>()
        val characterCounts = mutableMapOf<Long, Int>()
        if (!index.isEmpty) {
            pages.filter { it.page <= maxPage }.forEach { page ->
                page.paragraphs.forEach { paragraph ->
                    index.find(paragraph).forEach { hit ->
                        val key = normalize(paragraph.substring(hit.start, hit.end))
                        nameCounts[key] = (nameCounts[key] ?: 0) + 1
                        characterCounts[hit.characterId] = (characterCounts[hit.characterId] ?: 0) + 1
                    }
                }
            }
        }

        val characters = visible.map { (character, names) ->
            val current = currentName(names, maxPage, nameCounts)
            VisibleCharacter(
                id = character.id,
                name = current.name,
                otherNames = names.filter { it !== current }.sortedBy { it.firstPage },
                firstPage = names.minOf { it.firstPage },
                facts = character.facts.filter { it.page <= maxPage }.sortedBy { it.page },
                mentions = characterCounts[character.id] ?: 0
            )
        }.sortedWith(compareBy({ it.firstPage }, { it.id }))

        val ids = characters.mapTo(mutableSetOf()) { it.id }
        val relations = data.relations
            .filter { it.page <= maxPage && it.fromId in ids && it.toId in ids }
            .map {
                VisibleRelation(
                    id = it.id,
                    fromId = it.fromId,
                    toId = it.toId,
                    type = it.type,
                    label = it.label,
                    page = it.page,
                    endedAt = it.endsAtPage?.takeIf { end -> end <= maxPage }
                )
            }
        return CharactersSnapshot(maxPage, characters, relations, index)
    }

    private fun currentName(names: List<CharacterName>, maxPage: Int, counts: Map<String, Int>): CharacterName =
        names.filter { (it.primaryFrom ?: Int.MAX_VALUE) <= maxPage }.maxByOrNull { it.primaryFrom ?: 0 }
            ?: names.maxWith(
                compareBy<CharacterName> { counts[normalize(it.name)] ?: 0 }
                    .thenBy { it.kind == NameKind.NAME }
                    .thenByDescending { it.firstPage }
            )

    private const val MIN_NAME_LENGTH = 2
}
