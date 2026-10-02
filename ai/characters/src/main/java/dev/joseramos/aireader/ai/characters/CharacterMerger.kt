package dev.joseramos.aireader.ai.characters

import androidx.room.withTransaction
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.db.AppDatabase
import dev.joseramos.aireader.core.data.db.CharacterDao
import dev.joseramos.aireader.core.data.db.CharacterEntity
import dev.joseramos.aireader.core.data.db.CharacterFactEntity
import dev.joseramos.aireader.core.data.db.CharacterNameEntity
import dev.joseramos.aireader.core.data.db.NameKind
import dev.joseramos.aireader.core.data.db.RelationEntity
import dev.joseramos.aireader.core.data.db.RelationType
import java.text.Normalizer
import javax.inject.Inject

/**
 * Incorpora lo extraído de un capítulo a los personajes del libro (docs/02-diseno-tecnico.md §6.6):
 * - Las páginas fuera del capítulo se llevan a su última página: así nunca se desbloquea nada antes
 *   de tiempo.
 * - Los nombres nuevos se unen al personaje conocido; un personaje «nuevo» cuyo nombre completo ya
 *   existe es el mismo, y los pares de `same` se fusionan.
 * - Un cambio de nombre en la historia se guarda con `isPrimaryFrom`.
 * Todo va en una transacción: un capítulo se aplica entero o no se aplica.
 */
class CharacterMerger @Inject constructor(private val db: AppDatabase, private val dao: CharacterDao) {
    suspend fun merge(bookId: String, chapter: Chapter, extraction: Extraction) = db.withTransaction {
        val state = State(bookId, chapter).apply { load() }
        extraction.same.forEach { group -> state.mergeSame(group) }
        extraction.characters.forEach { state.addCharacter(it) }
        extraction.relations.forEach { state.addRelation(it) }
    }

    private inner class State(private val bookId: String, private val chapter: Chapter) {
        private val ids = mutableSetOf<Long>()
        private val names = mutableMapOf<Long, MutableList<CharacterNameEntity>>()

        /** Personajes fusionados: id eliminado → id que lo sustituye. */
        private val redirects = mutableMapOf<Long, Long>()

        /** Referencias de esta respuesta («c12», «n1») → id del personaje. */
        private val refs = mutableMapOf<String, Long>()

        suspend fun load() {
            dao.getCharacters(bookId).forEach { ids += it.id }
            dao.getNames(bookId).forEach { names.getOrPut(it.characterId) { mutableListOf() } += it }
        }

        private fun page(page: Int) = if (page in chapter.startPage..chapter.endPage) page else chapter.endPage

        private fun resolve(id: Long): Long? {
            var current = id
            while (true) current = redirects[current] ?: break
            return current.takeIf { it in ids }
        }

        private fun knownId(ref: String): Long? =
            ref.trim().removePrefix("c").toLongOrNull()?.takeIf { ref.trim().startsWith("c") }?.let(::resolve)

        private fun refId(ref: String): Long? = refs[ref.trim()]?.let(::resolve) ?: knownId(ref)

        /** Personaje que ya tiene este nombre completo (dos palabras o más), si solo hay uno. */
        private fun byFullName(name: String): Long? {
            if (!name.trim().contains(' ')) return null
            val key = normalize(name)
            return names.filterValues { list -> list.any { normalize(it.name) == key } }.keys.singleOrNull()
        }

        suspend fun mergeSame(group: List<String>) {
            val known = group.mapNotNull(::knownId).distinct().sorted()
            if (known.size < 2) return
            val keep = known.first()
            known.drop(1).forEach { other -> mergeInto(other, keep) }
        }

        private suspend fun mergeInto(from: Long, to: Long) {
            dao.moveNames(from, to)
            dao.moveFacts(from, to)
            dao.moveRelationsFrom(from, to)
            dao.moveRelationsTo(from, to)
            dao.deleteSelfRelations()
            dao.getCharacters(bookId).firstOrNull { it.id == from }?.let { dao.lowerFirstPage(to, it.firstPage) }
            dao.deleteCharacter(from)
            ids -= from
            redirects[from] = to
            names.remove(from)?.let { moved -> names.getOrPut(to) { mutableListOf() } += moved }
        }

        suspend fun addCharacter(item: ExtractedCharacter) {
            val valid = item.names.filter { it.name.isNotBlank() && it.name.length <= MAX_NAME_LENGTH }
            val id = knownId(item.ref)
                ?: valid.firstNotNullOfOrNull { byFullName(it.name) }
                ?: valid.takeIf { it.isNotEmpty() }?.let { list ->
                    dao.insertCharacter(CharacterEntity(bookId = bookId, firstPage = list.minOf { page(it.page) }))
                        .also { ids += it }
                }
                ?: return
            refs[item.ref.trim()] = id

            valid.forEach { addName(id, it.name, parseKind(it.kind), page(it.page), primaryFrom = null) }
            item.renamedTo?.takeIf { it.name.isNotBlank() && it.name.length <= MAX_NAME_LENGTH }?.let {
                addName(id, it.name, NameKind.NAME, page(it.page), primaryFrom = page(it.page))
            }
            val knownFacts = dao.getFacts(id).map { normalize(it.text) }.toMutableSet()
            item.facts.filter { it.text.isNotBlank() }.take(MAX_FACTS).forEach { fact ->
                val text = fact.text.trim().take(MAX_FACT_LENGTH)
                if (knownFacts.add(normalize(text))) {
                    dao.insertFact(
                        CharacterFactEntity(
                            characterId = id,
                            chapterId = chapter.id,
                            page = page(fact.page),
                            text = text
                        )
                    )
                }
            }
            (valid.map { page(it.page) } + listOfNotNull(item.renamedTo?.page?.let(::page))).minOrNull()?.let {
                dao.lowerFirstPage(id, it)
            }
        }

        private suspend fun addName(id: Long, name: String, kind: NameKind, page: Int, primaryFrom: Int?) {
            val list = names.getOrPut(id) { mutableListOf() }
            val existing = list.firstOrNull { normalize(it.name) == normalize(name) }
            if (existing == null) {
                val entity = CharacterNameEntity(
                    characterId = id,
                    name = name.trim(),
                    kind = kind,
                    firstPage = page,
                    isPrimaryFrom = primaryFrom
                )
                list += entity.copy(id = dao.insertName(entity))
            } else if (primaryFrom != null && existing.isPrimaryFrom == null) {
                dao.setPrimaryFrom(existing.id, primaryFrom)
                list[list.indexOf(existing)] = existing.copy(isPrimaryFrom = primaryFrom)
            }
        }

        suspend fun addRelation(item: ExtractedRelation) {
            val from = refId(item.from) ?: return
            val to = refId(item.to) ?: return
            if (from == to) return
            val type = runCatching {
                RelationType.valueOf(item.type.trim().uppercase())
            }.getOrDefault(RelationType.OTHER)
            val page = page(item.page)
            val existing = dao.getRelations(bookId).filter {
                it.endsAtPage == null && ((it.fromId == from && it.toId == to) || (it.fromId == to && it.toId == from))
            }
            if (item.ended) {
                (existing.lastOrNull { it.type == type } ?: existing.lastOrNull())?.let { dao.endRelation(it.id, page) }
                return
            }
            val label = item.label.trim().take(MAX_LABEL_LENGTH)
            if (existing.any { it.type == type && it.label.equals(label, ignoreCase = true) }) return
            dao.insertRelation(
                RelationEntity(bookId = bookId, fromId = from, toId = to, type = type, label = label, page = page)
            )
        }
    }

    private companion object {
        const val MAX_NAME_LENGTH = 80
        const val MAX_FACTS = 3
        const val MAX_FACT_LENGTH = 300
        const val MAX_LABEL_LENGTH = 60

        fun parseKind(kind: String) = runCatching {
            NameKind.valueOf(kind.trim().uppercase())
        }.getOrDefault(NameKind.NAME)
    }
}

/** Nombre sin tildes, en minúsculas y con espacios simples, para comparar. */
internal fun normalize(text: String): String = Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "")
    .lowercase()
    .replace(Regex("\\s+"), " ")
