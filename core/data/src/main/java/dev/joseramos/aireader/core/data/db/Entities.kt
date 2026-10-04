package dev.joseramos.aireader.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.ForeignKey.Companion.SET_NULL
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

// Modelo de datos de docs/02-diseno-tecnico.md §2.2. Todo cuelga de BookEntity con borrado
// en cascada, para que eliminar un libro no deje datos huérfanos (fallo del prototipo).

/**
 * Estado de preparación de un libro. [TEXT_READY]: texto y capítulos listos (se puede leer y
 * escuchar), pero faltan los embeddings porque el modelo no está descargado.
 */
enum class IndexStatus { PENDING, EXTRACTING_TEXT, TEXT_READY, EMBEDDING, READY, FAILED }

enum class ChapterSource { OUTLINE, HEURISTIC, LLM, BLOCKS }

/** [RECAP]: repaso «Hasta ahora…» de lo leído hasta [SummaryEntity.untilPage]. */
enum class SummaryKind { CHAPTER_SHORT, CHAPTER_LONG, BOOK, RECAP }

enum class ChatRole { USER, ASSISTANT }

/**
 * Tipo de modelo descargado. `TTS_VOICE` ya no se usa (las voces de Piper se sustituyeron por la
 * voz del sistema), pero se conserva para poder leer los registros antiguos y borrarlos.
 */
enum class ModelKind { TTS_VOICE, EMBEDDING }

/** Clase de documento: decide el estilo de los resúmenes y si hay personajes (docs/02 §6.4). */
enum class DocumentType { LITERATURE, SCIENTIFIC, EDUCATIONAL, GENERIC }

/** [USER]: el tipo lo eligió el usuario y la indexación no lo vuelve a calcular. */
enum class DocumentTypeSource { AUTO, USER }

enum class NameKind { NAME, SURNAME, NICKNAME, TITLE }

enum class RelationType { SPOUSE, FAMILY, FRIEND, ROMANCE, ENEMY, ACQUAINTANCE, MET, WORKS_WITH, OTHER }

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val fileName: String,
    val filePath: String,
    val pageCount: Int,
    val coverPath: String?,
    val importedAt: Long,
    val lastOpenedAt: Long?,
    val indexStatus: IndexStatus,
    val indexProgress: Float,
    val cleanerVersion: Int,
    /** `null` hasta que la indexación lo clasifica. */
    val documentType: DocumentType? = null,
    @ColumnInfo(defaultValue = "AUTO") val documentTypeSource: DocumentTypeSource = DocumentTypeSource.AUTO,
    /**
     * Si las etapas de la indexación que usan la IA (capítulos y tipo de documento cuando las
     * heurísticas no bastan) ya se hicieron con clave de API. Un libro importado sin clave se
     * vuelve a repasar al introducirla (ver `ApiKeyObserver`).
     */
    @ColumnInfo(defaultValue = "0") val aiPrepared: Boolean = false
)

/**
 * Entrada del índice del libro. [level] 0 son los capítulos (o temas), que son los que se resumen y
 * por los que salta la voz; 1 y 2 son sus apartados y subapartados, que solo aparecen en el menú. Los
 * apartados llevan el [number] de su capítulo.
 */
@Entity(
    tableName = "chapters",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = CASCADE)],
    indices = [Index("bookId")]
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val number: Int,
    val title: String,
    val startPage: Int,
    val endPage: Int,
    val source: ChapterSource,
    @ColumnInfo(defaultValue = "0") val level: Int = 0
)

@Entity(
    tableName = "page_texts",
    primaryKeys = ["bookId", "page"],
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = CASCADE)]
)
data class PageTextEntity(
    val bookId: String,
    val page: Int,
    val rawText: String,
    val cleanText: String,
    /** Párrafos del texto limpio, como array JSON de cadenas. */
    val paragraphsJson: String,
    val isScanned: Boolean,
    val cleanerVersion: Int,
    /** Nivel de cada párrafo (0 texto, 1… títulos de mayor a menor), como array JSON de enteros. */
    @ColumnInfo(defaultValue = "[]") val levelsJson: String = "[]"
)

/**
 * Líneas de una página con su geometría (posición, tamaño de letra, negrita), como array JSON de
 * `TextLine`. Va aparte de [PageTextEntity] para no cargarlo cada vez que el lector lee el texto.
 */
@Entity(
    tableName = "page_layouts",
    primaryKeys = ["bookId", "page"],
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = CASCADE)]
)
data class PageLayoutEntity(val bookId: String, val page: Int, val linesJson: String)

@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = CASCADE),
        ForeignKey(ChapterEntity::class, ["id"], ["chapterId"], onDelete = SET_NULL)
    ],
    indices = [Index("bookId"), Index("chapterId")]
)
data class ChunkEntity(
    /** Coincide con el rowid de SQLite, que usa la tabla FTS para enlazar. */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val chapterId: Long?,
    val ordinal: Int,
    val text: String,
    val startPage: Int,
    val endPage: Int,
    val charStart: Int,
    val charEnd: Int,
    val tokenCount: Int
)

/**
 * Índice de texto completo sobre [ChunkEntity.text] para la búsqueda léxica del RAG. `unicode61` con
 * `remove_diacritics=1` pliega las tildes (y las mayúsculas) al indexar y al buscar: «mato» encuentra «mató».
 * Se usa 1 y no 2 porque con minSdk 28 (SQLite 3.22) solo existen 0 y 1.
 */
@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=1"],
    contentEntity = ChunkEntity::class
)
@Entity(tableName = "chunks_fts")
data class ChunkFtsEntity(@PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long, val text: String)

@Entity(
    tableName = "chunk_embeddings",
    foreignKeys = [ForeignKey(ChunkEntity::class, ["id"], ["chunkId"], onDelete = CASCADE)]
)
data class ChunkEmbeddingEntity(
    @PrimaryKey val chunkId: Long,
    val modelId: String,
    val dim: Int,
    /** Vector float32 normalizado L2, en little-endian. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val vector: ByteArray
) {
    override fun equals(other: Any?) = other is ChunkEmbeddingEntity &&
        chunkId == other.chunkId &&
        modelId == other.modelId &&
        vector.contentEquals(other.vector)

    override fun hashCode() = 31 * chunkId.hashCode() + vector.contentHashCode()
}

@Entity(
    tableName = "summaries",
    foreignKeys = [
        ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = CASCADE),
        ForeignKey(ChapterEntity::class, ["id"], ["chapterId"], onDelete = CASCADE)
    ],
    indices = [Index("bookId"), Index("chapterId")]
)
data class SummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val chapterId: Long?,
    val kind: SummaryKind,
    val text: String,
    val model: String,
    val createdAt: Long,
    /** Última página que cubre un repaso ([SummaryKind.RECAP]); `null` en los demás. */
    val untilPage: Int? = null
)

@Entity(
    tableName = "reading_positions",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = CASCADE)]
)
data class ReadingPositionEntity(
    @PrimaryKey val bookId: String,
    val page: Int,
    val paragraphIndex: Int,
    val phraseIndex: Int,
    val updatedAt: Long,
    /**
     * Página más avanzada leída (por scroll o por voz). Nunca baja: es la referencia de todas
     * las reglas sin spoilers.
     */
    @ColumnInfo(defaultValue = "0") val maxPage: Int = page
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = CASCADE)],
    // El orden importa: cubre tanto los filtros por libro como, con createdAt, el ORDER BY DESC LIMIT 1
    // de `BookDao.observeAll()` (el marcapáginas más reciente de cada libro).
    indices = [Index(value = ["bookId", "createdAt"])]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val page: Int,
    val note: String?,
    val createdAt: Long
)

/**
 * Subrayado de un fragmento exacto de texto (solo modo texto): [startOffset]/[endOffset] son
 * caracteres dentro del string de [paragraph] en esa [page], la misma coordenada que ya usa la
 * voz para la frase que suena.
 */
@Entity(
    tableName = "highlights",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = CASCADE)],
    indices = [Index(value = ["bookId", "createdAt"])]
)
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val page: Int,
    val paragraph: Int,
    val startOffset: Int,
    val endOffset: Int,
    val note: String?,
    val createdAt: Long
)

/** Personaje de una novela. Sus nombres, datos y relaciones llevan la página donde aparecen. */
@Entity(
    tableName = "characters",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = CASCADE)],
    indices = [Index("bookId")]
)
data class CharacterEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val bookId: String, val firstPage: Int)

@Entity(
    tableName = "character_names",
    foreignKeys = [ForeignKey(CharacterEntity::class, ["id"], ["characterId"], onDelete = CASCADE)],
    indices = [Index("characterId")]
)
data class CharacterNameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val characterId: Long,
    val name: String,
    val kind: NameKind,
    val firstPage: Int,
    /** Si no es `null`, desde esta página es el nombre principal (cambio de nombre en la historia). */
    val isPrimaryFrom: Int? = null
)

@Entity(
    tableName = "character_facts",
    foreignKeys = [
        ForeignKey(CharacterEntity::class, ["id"], ["characterId"], onDelete = CASCADE),
        ForeignKey(ChapterEntity::class, ["id"], ["chapterId"], onDelete = CASCADE)
    ],
    indices = [Index("characterId"), Index("chapterId")]
)
data class CharacterFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val characterId: Long,
    val chapterId: Long,
    val page: Int,
    val text: String
)

@Entity(
    tableName = "relations",
    foreignKeys = [
        ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = CASCADE),
        ForeignKey(CharacterEntity::class, ["id"], ["fromId"], onDelete = CASCADE),
        ForeignKey(CharacterEntity::class, ["id"], ["toId"], onDelete = CASCADE)
    ],
    indices = [Index("bookId"), Index("fromId"), Index("toId")]
)
data class RelationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val fromId: Long,
    val toId: Long,
    val type: RelationType,
    /** Cómo se lee la relación desde [fromId]: «hermano de», «se encontró con»… */
    val label: String,
    val page: Int,
    /** Página donde la relación deja de ser vigente (ruptura, muerte…), si ocurre. */
    val endsAtPage: Int? = null
)

/** Capítulo ya analizado en busca de personajes: el análisis es reanudable por capítulos. */
@Entity(
    tableName = "character_scans",
    primaryKeys = ["bookId", "chapterId"],
    foreignKeys = [
        ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = CASCADE),
        ForeignKey(ChapterEntity::class, ["id"], ["chapterId"], onDelete = CASCADE)
    ],
    indices = [Index("chapterId")]
)
data class CharacterScanEntity(val bookId: String, val chapterId: Long, val model: String, val scannedAt: Long)

@Entity(
    tableName = "chat_threads",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = CASCADE)],
    indices = [Index("bookId")]
)
data class ChatThreadEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val bookId: String, val createdAt: Long)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [ForeignKey(ChatThreadEntity::class, ["id"], ["threadId"], onDelete = CASCADE)],
    indices = [Index("threadId")]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val threadId: Long,
    val role: ChatRole,
    val text: String,
    /** Citas validadas de la respuesta, como array JSON de páginas. */
    val citationsJson: String,
    val createdAt: Long
)

@Entity(tableName = "downloaded_models")
data class DownloadedModelEntity(
    @PrimaryKey val id: String,
    val kind: ModelKind,
    val version: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
    val downloadedAt: Long
)
