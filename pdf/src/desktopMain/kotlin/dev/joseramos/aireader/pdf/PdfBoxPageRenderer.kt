package dev.joseramos.aireader.pdf

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.joseramos.aireader.core.common.AppDirs
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer

/**
 * Renderiza páginas de un PDF con Apache PDFBox. `PDFRenderer` no admite varios renders a la vez, así que todo el
 * acceso pasa por un [Mutex]. Mantiene una caché LRU acotada por memoria para no volver a renderizar las páginas
 * vecinas.
 */
internal class PdfBoxPageRenderer(file: File, tempDir: File) : PdfPageRenderer {
    private val document: PDDocument = PDDocument.load(
        file,
        MemoryUsageSetting.setupMixed(MAIN_MEMORY_BYTES).setTempDir(tempDir)
    )
    private val renderer = PDFRenderer(document)
    private val mutex = Mutex()
    private var closed = false
    private val cache = object : LinkedHashMap<CacheKey, ImageBitmap>(CACHE_INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, ImageBitmap>): Boolean {
            if (cacheBytes() <= CACHE_BYTES) return false
            // Con una sola página enorme no se descarta nada: se queda la última renderizada.
            return size > 1
        }
    }

    override val pageCount: Int = document.numberOfPages

    /** Medirlas es barato (no hay que renderizar nada), así que no se guardan en disco. */
    override val pageSizes: List<PageSize> by lazy {
        List(pageCount) { index ->
            val (width, height) = pageSize(index)
            PageSize(width.roundToInt(), height.roundToInt())
        }
    }

    /** Ancho y alto de la página [index] (base 0) tal como se ve: con su rotación aplicada. */
    private fun pageSize(index: Int): Pair<Float, Float> {
        val page = document.getPage(index)
        val box = page.cropBox ?: page.mediaBox
        return if (page.rotation % HALF_TURN == 0) box.width to box.height else box.height to box.width
    }

    override suspend fun render(index: Int, widthPx: Int): ImageBitmap {
        val key = CacheKey(index, widthPx)
        synchronized(cache) { cache[key] }?.let { return it }
        return mutex.withLock {
            check(!closed) { "El PDF ya está cerrado" }
            withContext(Dispatchers.IO) {
                synchronized(cache) { cache[key] } ?: renderImage(index, widthPx).toComposeImageBitmap().also {
                    synchronized(cache) { cache[key] = it }
                }
            }
        }
    }

    /** Página [index] (base 0) a [widthPx] de ancho, manteniendo la proporción. */
    internal fun renderImage(index: Int, widthPx: Int): BufferedImage {
        val (width, _) = pageSize(index)
        return renderer.renderImage(index, widthPx / width.coerceAtLeast(1f), ImageType.RGB)
    }

    private fun cacheBytes(): Long = cache.values.sumOf { it.width.toLong() * it.height * BYTES_PER_PIXEL }

    /** Cierra esperando (bloqueando el hilo) a que termine un render en curso: ver [release]. */
    override fun close() = runBlocking { mutex.withLock { closeLocked() } }

    /** Cierra esperando a que termine el render en curso (cerrar a mitad de un render falla). */
    override suspend fun release() = mutex.withLock { closeLocked() }

    private fun closeLocked() {
        if (closed) return
        closed = true
        synchronized(cache) { cache.clear() }
        document.close()
    }

    private data class CacheKey(val index: Int, val width: Int)

    companion object {
        private const val MAIN_MEMORY_BYTES = 16L * 1024 * 1024

        /** Unas 5 páginas a resolución de pantalla (~1080×1500 px ARGB). */
        private const val CACHE_BYTES = 40L * 1024 * 1024
        private const val CACHE_INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
        private const val BYTES_PER_PIXEL = 4
        private const val HALF_TURN = 180
    }
}

/** Abre PDF con Apache PDFBox. */
internal class PdfBoxRendererFactory(private val dirs: AppDirs) : PdfRendererFactory {
    override fun open(file: File, sizesCache: File?): PdfPageRenderer = PdfBoxPageRenderer(file, scratchDir())

    override fun writeCover(pdf: File, cover: File, widthPx: Int): Int = try {
        PdfBoxPageRenderer(pdf, scratchDir()).use { renderer ->
            cover.parentFile?.mkdirs()
            if (!ImageIO.write(
                    renderer.renderImage(0, widthPx),
                    "png",
                    cover
                )
            ) {
                throw IOException("No se pudo guardar la portada")
            }
            renderer.pageCount
        }
    } catch (e: InvalidPasswordException) {
        throw SecurityException("El PDF está protegido con contraseña.", e)
    }

    /** Donde PDFBox guarda lo que no cabe en memoria: tiene que existir. */
    private fun scratchDir(): File = dirs.cache.also { it.mkdirs() }
}
