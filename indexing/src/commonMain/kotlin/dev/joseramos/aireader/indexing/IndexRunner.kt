package dev.joseramos.aireader.indexing

import dev.joseramos.aireader.core.common.Log
import dev.joseramos.aireader.core.data.db.BookDao
import dev.joseramos.aireader.core.data.db.BookEntity
import dev.joseramos.aireader.core.data.db.IndexStatus
import dev.joseramos.aireader.core.data.settings.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Etapa opcional del chat (F7): fragmentos y embeddings. La aporta el módulo de RAG; mientras no
 * exista, la indexación termina tras el texto y los capítulos.
 */
interface EmbeddingStage {
    /**
     * Trocea el libro en fragmentos (con su índice de texto completo) si aún no los tiene. No usa el
     * modelo de embeddings: así el chat puede buscar por palabras aunque el modelo falte o falle.
     */
    suspend fun prepareChunks(bookId: String)

    /** `false` si no se puede ejecutar todavía (por ejemplo, falta descargar el modelo). */
    suspend fun run(bookId: String, onProgress: suspend (Float) -> Unit): Boolean
}

/**
 * Arranca el análisis de personajes (F10) cuando un libro termina de indexarse. Lo aporta
 * `:ai:characters`, que decide si toca (novela, análisis automático y clave de API).
 */
interface CharacterAnalysisTrigger {
    suspend fun onBookIndexed(bookId: String)
}

/** Qué hacer con un libro tras un intento de indexarlo. */
enum class IndexResult {
    /** Indexado (o ya no existe). */
    SUCCESS,

    /** Falló, pero merece otro intento más tarde. */
    RETRY,

    /** Falló y ya no se reintenta: el libro queda marcado como fallido. */
    FAILED
}

/**
 * Indexa un libro: texto → capítulos → tipo de documento → embeddings. Cada etapa guarda su progreso en Room,
 * así que si algo detiene el trabajo (Android, el cierre de la app), al relanzarlo continúa donde estaba. Si el
 * libro se indexó sin clave de API y ahora la hay, repasa las etapas que usan la IA ([BookEntity.aiPrepared]).
 * No sabe cómo se programa: lo hace [IndexScheduler] en cada plataforma.
 */
class IndexRunner(
    private val bookDao: BookDao,
    private val textIndexer: TextIndexer,
    private val chapterDetector: ChapterDetector,
    private val documentTypeDetector: DocumentTypeDetector,
    private val embeddingStage: EmbeddingStage?,
    private val characterAnalysis: CharacterAnalysisTrigger?,
    private val crashGuard: StageCrashGuard,
    private val secrets: SecretStore
) {
    /**
     * [attempt] es el intento actual (0 el primero). [onStart] se llama con el título del libro antes de empezar
     * (Android pasa a primer plano); si falla, la indexación sigue igual.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun index(bookId: String, attempt: Int, onStart: suspend (title: String) -> Unit = {}): IndexResult {
        val book = bookDao.get(bookId) ?: return IndexResult.SUCCESS
        // Si Android no deja pasar a primer plano (por ejemplo, desde segundo plano en Android 12+),
        // el trabajo sigue igual; como es reanudable, si lo detienen continuará donde se quedó.
        runCatching { onStart(book.title) }
            .onFailure { Log.w(TAG, "Sin primer plano para $bookId", it) }

        return try {
            val upgraded = text(book)
            // Un libro ya listo que solo se repasa (por ejemplo, al introducir la clave) no vuelve a «preparando».
            if (!upgraded && book.indexStatus != IndexStatus.READY) {
                bookDao.updateIndexState(bookId, IndexStatus.EMBEDDING, TEXT_SHARE)
            }
            // La clave se mira tras el texto, que es lo largo: así cuenta aunque se haya introducido mientras.
            val withAi = secrets.hasApiKey.first()
            val retryWithAi = withAi && !book.aiPrepared
            chapterDetector.run(book, upgrade = upgraded, retryWithAi = retryWithAi)
            documentTypeDetector.run(book, retryWithAi = retryWithAi)

            val embedded = searchIndex(bookId)
            // Si los embeddings faltan o fallan, el libro ya se puede leer y escuchar, y el chat busca por palabras.
            bookDao.updateIndexState(
                bookId,
                if (embedded) IndexStatus.READY else IndexStatus.TEXT_READY,
                if (embedded) 1f else TEXT_SHARE
            )
            if (withAi) bookDao.setAiPrepared(bookId, true)
            characterAnalysis?.let { runCatching { it.onBookIndexed(bookId) } }
            IndexResult.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Fallo indexando $bookId (intento ${attempt + 1})", e)
            if (attempt < MAX_ATTEMPTS) {
                IndexResult.RETRY
            } else {
                bookDao.updateIndexState(bookId, IndexStatus.FAILED, 0f)
                IndexResult.FAILED
            }
        }
    }

    /**
     * Etapa de texto. Si el trabajo se relanza (Android lo detuvo o la app se cerró) y el texto ya
     * estaba, no se vuelve a «extrayendo texto 0 %». Si el texto es de una versión anterior del
     * limpiador, se rehace sin cambiar el estado, porque el libro se sigue pudiendo leer con el texto
     * antiguo mientras tanto; en ese caso devuelve `true`.
     */
    private suspend fun text(book: BookEntity): Boolean {
        if (textIndexer.isComplete(book)) return false
        val upgrade = textIndexer.hasOutdatedText(book)
        Log.i(TAG, "${if (upgrade) "Actualizando" else "Extrayendo"} el texto de ${book.id} (${book.pageCount} págs.)")
        if (upgrade) {
            textIndexer.run(book) {}
        } else {
            bookDao.updateIndexState(book.id, IndexStatus.EXTRACTING_TEXT, 0f)
            textIndexer.run(book) { bookDao.updateIndexState(book.id, IndexStatus.EXTRACTING_TEXT, it * TEXT_SHARE) }
        }
        return upgrade
    }

    /**
     * Fragmentos y vectores para el chat. Los vectores son la etapa más pesada (un modelo ONNX en el
     * móvil). Cada etapa va protegida por [StageCrashGuard]: si falla o tumba la app dos veces seguidas
     * con este libro, se salta y el libro queda listo para leer, en lugar de reintentarlo para siempre.
     */
    private suspend fun searchIndex(bookId: String): Boolean {
        val stage = embeddingStage ?: return true
        val chunked = guarded(StageCrashGuard.CHUNKS, bookId) {
            stage.prepareChunks(bookId)
            true
        }
        return chunked &&
            guarded(StageCrashGuard.EMBEDDINGS, bookId) {
                stage.run(bookId) {
                    bookDao.updateIndexState(bookId, IndexStatus.EMBEDDING, TEXT_SHARE + it * (1 - TEXT_SHARE))
                }
            }
    }

    @Suppress("TooGenericExceptionCaught") // Incluye errores nativos y de memoria del modelo.
    private suspend fun guarded(name: String, bookId: String, block: suspend () -> Boolean): Boolean {
        if (crashGuard.shouldSkip(name, bookId)) {
            Log.w(TAG, "Se omite la etapa $name de $bookId: no terminó en los últimos intentos o no funciona aquí")
            return false
        }
        crashGuard.begin(name, bookId)
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkageError) {
            // Falta o no carga una biblioteca nativa: no se arreglará reintentando hasta actualizar la app.
            Log.e(TAG, "La etapa $name no puede funcionar en este dispositivo", e)
            crashGuard.markUnsupported(name)
            false
        } catch (e: Throwable) {
            Log.e(TAG, "Fallo en la etapa $name de $bookId", e)
            false
        } finally {
            crashGuard.end(name, bookId)
        }
    }

    private companion object {
        const val TAG = "IndexWorker"
        const val MAX_ATTEMPTS = 3

        /** Parte de la barra de progreso que corresponde al texto y los capítulos. */
        const val TEXT_SHARE = 0.5f
    }
}
