package dev.joseramos.aireader.core.data.book

import dev.joseramos.aireader.core.common.currentTimeMillis
import dev.joseramos.aireader.core.data.db.ChatDao
import dev.joseramos.aireader.core.data.db.ChatMessageEntity
import dev.joseramos.aireader.core.data.db.ChatRole
import dev.joseramos.aireader.core.data.db.ChatThreadEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** De dónde sale una fuente: texto del libro o los hechos clave (generados) de un capítulo. */
enum class SourceKind { BOOK, KEY_POINTS }

/**
 * Fragmento enviado al modelo para responder, tal cual se le mandó: [number] es su id en el prompt
 * y [startPage]–[endPage] sus páginas (base 1). [chapter] es el título del capítulo, si se sabe.
 */
@Serializable
data class ChatSource(
    val number: Int,
    val kind: SourceKind,
    val text: String,
    val startPage: Int,
    val endPage: Int,
    val chapter: String? = null
) {
    /** Si la respuesta cita alguna de sus páginas. */
    fun isCitedBy(citations: List<Int>): Boolean = citations.any { it in startPage..endPage }
}

/**
 * Mensaje del chat con el libro. [citations] son las páginas citadas (ya validadas) y [sources], los
 * fragmentos que se enviaron al modelo para responder (vacío en las preguntas y en respuestas antiguas).
 */
data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val citations: List<Int>,
    val sources: List<ChatSource> = emptyList()
)

/** Conversaciones con un libro: una activa por libro; «Nueva conversación» crea otra. */
class ChatRepository(private val dao: ChatDao) {
    suspend fun currentThread(bookId: String): Long = dao.latestThread(bookId)?.id ?: newThread(bookId)

    suspend fun newThread(bookId: String): Long =
        dao.insertThread(ChatThreadEntity(bookId = bookId, createdAt = currentTimeMillis()))

    fun observeMessages(threadId: Long): Flow<List<ChatMessage>> =
        dao.observeMessages(threadId).map { list -> list.map { it.toMessage() } }

    suspend fun messages(threadId: Long): List<ChatMessage> = dao.getMessages(threadId).map { it.toMessage() }

    /** La fuente número [number] de la respuesta [messageId], si existe. */
    suspend fun source(messageId: Long, number: Int): ChatSource? =
        dao.getMessage(messageId)?.toMessage()?.sources?.firstOrNull { it.number == number }

    suspend fun add(
        threadId: Long,
        role: ChatRole,
        text: String,
        citations: List<Int> = emptyList(),
        sources: List<ChatSource> = emptyList()
    ): Long = dao.insertMessage(
        ChatMessageEntity(
            threadId = threadId,
            role = role,
            text = text,
            citationsJson = Json.encodeToString(citations),
            createdAt = currentTimeMillis(),
            sourcesJson = Json.encodeToString(sources)
        )
    )
}

private fun ChatMessageEntity.toMessage() = ChatMessage(
    id = id,
    role = role,
    text = text,
    citations = runCatching { Json.decodeFromString<List<Int>>(citationsJson) }.getOrDefault(emptyList()),
    sources = runCatching { Json.decodeFromString<List<ChatSource>>(sourcesJson) }.getOrDefault(emptyList())
)
