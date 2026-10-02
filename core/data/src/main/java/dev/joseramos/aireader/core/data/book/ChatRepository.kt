package dev.joseramos.aireader.core.data.book

import dev.joseramos.aireader.core.data.db.ChatDao
import dev.joseramos.aireader.core.data.db.ChatMessageEntity
import dev.joseramos.aireader.core.data.db.ChatRole
import dev.joseramos.aireader.core.data.db.ChatThreadEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Mensaje del chat con el libro. [citations] son las páginas citadas (ya validadas). */
data class ChatMessage(val id: Long, val role: ChatRole, val text: String, val citations: List<Int>)

/** Conversaciones con un libro: una activa por libro; «Nueva conversación» crea otra. */
@Singleton
class ChatRepository @Inject constructor(private val dao: ChatDao) {
    suspend fun currentThread(bookId: String): Long = dao.latestThread(bookId)?.id ?: newThread(bookId)

    suspend fun newThread(bookId: String): Long =
        dao.insertThread(ChatThreadEntity(bookId = bookId, createdAt = System.currentTimeMillis()))

    fun observeMessages(threadId: Long): Flow<List<ChatMessage>> =
        dao.observeMessages(threadId).map { list -> list.map { it.toMessage() } }

    suspend fun messages(threadId: Long): List<ChatMessage> = dao.getMessages(threadId).map { it.toMessage() }

    suspend fun add(threadId: Long, role: ChatRole, text: String, citations: List<Int> = emptyList()): Long =
        dao.insertMessage(
            ChatMessageEntity(
                threadId = threadId,
                role = role,
                text = text,
                citationsJson = Json.encodeToString(citations),
                createdAt = System.currentTimeMillis()
            )
        )
}

private fun ChatMessageEntity.toMessage() = ChatMessage(
    id = id,
    role = role,
    text = text,
    citations = runCatching { Json.decodeFromString<List<Int>>(citationsJson) }.getOrDefault(emptyList())
)
