package dev.joseramos.aireader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Renderiza páginas de un PDF a bitmaps con el `PdfRenderer` del sistema. `PdfRenderer` solo
 * admite una página abierta a la vez, así que todo el acceso pasa por un [Mutex]. Mantiene una
 * caché LRU acotada por memoria para no volver a renderizar las páginas vecinas.
 */
internal class AndroidPdfPageRenderer(private val file: File, private val sizesCache: File? = null) : PdfPageRenderer {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val mutex = Mutex()
    private var closed = false
    private val cache = object : LruCache<CacheKey, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: CacheKey, value: Bitmap) = value.byteCount
    }

    override val pageCount: Int = renderer.pageCount

    /**
     * Tamaño de cada página en puntos PDF, para reservar el hueco antes de renderizar. Medirlas obliga
     * a abrir todas las páginas (casi un segundo en un libro de 500), así que se calcula solo si se
     * pide y, con [sizesCache], se guarda la primera vez. Hay que pedirlo fuera del hilo principal.
     */
    override val pageSizes: List<PageSize> by lazy { readSizes() ?: measureSizes().also(::writeSizes) }

    private fun measureSizes(): List<PageSize> = List(pageCount) { index ->
        renderer.openPage(index).use { PageSize(it.width, it.height) }
    }

    /** Los tamaños guardados, si son de este mismo fichero (tamaño, fecha y número de páginas). */
    private fun readSizes(): List<PageSize>? = runCatching {
        val cache = sizesCache?.takeIf { it.exists() } ?: return null
        DataInputStream(cache.inputStream().buffered()).use { input ->
            if (input.readLong() != file.length() || input.readLong() != file.lastModified()) return null
            if (input.readInt() != pageCount) return null
            List(pageCount) { PageSize(input.readInt(), input.readInt()) }
        }
    }.getOrNull()

    private fun writeSizes(sizes: List<PageSize>) {
        val cache = sizesCache ?: return
        runCatching {
            cache.parentFile?.mkdirs()
            DataOutputStream(cache.outputStream().buffered()).use { output ->
                output.writeLong(file.length())
                output.writeLong(file.lastModified())
                output.writeInt(sizes.size)
                sizes.forEach {
                    output.writeInt(it.width)
                    output.writeInt(it.height)
                }
            }
        }
    }

    /** Página [index] (base 0) renderizada a [widthPx] de ancho, manteniendo la proporción. */
    override suspend fun render(index: Int, widthPx: Int): ImageBitmap = renderBitmap(index, widthPx).asImageBitmap()

    private suspend fun renderBitmap(index: Int, widthPx: Int): Bitmap {
        val key = CacheKey(index, widthPx)
        cache.get(key)?.let { return it }
        return mutex.withLock {
            check(!closed) { "El PDF ya está cerrado" }
            withContext(Dispatchers.IO) {
                cache.get(key) ?: renderer.openPage(index).use { page ->
                    val height = (widthPx * page.height.toFloat() / page.width).roundToInt().coerceAtLeast(1)
                    Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        cache.put(key, bitmap)
                    }
                }
            }
        }
    }

    /** Cierra esperando (bloqueando el hilo) a que termine un render en curso: ver [release]. */
    override fun close() = runBlocking { mutex.withLock { closeLocked() } }

    /** Cierra esperando a que termine el render en curso (cerrar a mitad de un render falla). */
    override suspend fun release() = mutex.withLock { closeLocked() }

    private fun closeLocked() {
        if (closed) return
        closed = true
        cache.evictAll()
        renderer.close()
        descriptor.close()
    }

    private data class CacheKey(val index: Int, val width: Int)

    companion object {
        /** Unas 5 páginas a resolución de pantalla (~1080×1500 px ARGB). */
        private const val CACHE_BYTES = 40 * 1024 * 1024
        internal const val PNG_QUALITY = 100
    }

    internal fun renderBlocking(index: Int, widthPx: Int): Bitmap = renderer.openPage(index).use { page ->
        val height = (widthPx * page.height.toFloat() / page.width).roundToInt().coerceAtLeast(1)
        Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }
}

/** Abre PDF con el `PdfRenderer` del sistema. */
internal class AndroidPdfRendererFactory : PdfRendererFactory {
    override fun open(file: File, sizesCache: File?): PdfPageRenderer = AndroidPdfPageRenderer(file, sizesCache)

    override fun writeCover(pdf: File, cover: File, widthPx: Int): Int = AndroidPdfPageRenderer(pdf).use { renderer ->
        renderer.renderBlocking(0, widthPx).let { bitmap ->
            cover.parentFile?.mkdirs()
            cover.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, AndroidPdfPageRenderer.PNG_QUALITY, it)
            }
        }
        renderer.pageCount
    }
}
