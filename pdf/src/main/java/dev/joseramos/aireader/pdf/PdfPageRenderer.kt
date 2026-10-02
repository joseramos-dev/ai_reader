package dev.joseramos.aireader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.util.Size
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Renderiza páginas de un PDF a bitmaps con el `PdfRenderer` del sistema. `PdfRenderer` solo
 * admite una página abierta a la vez, así que todo el acceso pasa por un [Mutex]. Mantiene una
 * caché LRU acotada por memoria para no volver a renderizar las páginas vecinas.
 */
class PdfPageRenderer(file: File) : Closeable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val mutex = Mutex()
    private var closed = false
    private val cache = object : LruCache<CacheKey, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: CacheKey, value: Bitmap) = value.byteCount
    }

    val pageCount: Int = renderer.pageCount

    /** Tamaño de cada página en puntos PDF, para reservar el hueco antes de renderizar. */
    val pageSizes: List<Size> = List(pageCount) { index ->
        renderer.openPage(index).use { Size(it.width, it.height) }
    }

    /** Página [index] (base 0) renderizada a [widthPx] de ancho, manteniendo la proporción. */
    suspend fun render(index: Int, widthPx: Int): Bitmap {
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

    override fun close() {
        closed = true
        cache.evictAll()
        renderer.close()
        descriptor.close()
    }

    /** Cierra esperando a que termine el render en curso (cerrar a mitad de un render falla). */
    suspend fun release() = mutex.withLock { close() }

    private data class CacheKey(val index: Int, val width: Int)

    companion object {
        /** Unas 5 páginas a resolución de pantalla (~1080×1500 px ARGB). */
        private const val CACHE_BYTES = 40 * 1024 * 1024
        private const val PNG_QUALITY = 100

        /** Abre el PDF solo para contar páginas y generar la portada (página 1) como PNG. */
        fun writeCover(pdf: File, cover: File, widthPx: Int): Int = PdfPageRenderer(pdf).use { renderer ->
            renderer.renderBlocking(0, widthPx).let { bitmap ->
                cover.parentFile?.mkdirs()
                cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
            }
            renderer.pageCount
        }
    }

    private fun renderBlocking(index: Int, widthPx: Int): Bitmap = renderer.openPage(index).use { page ->
        val height = (widthPx * page.height.toFloat() / page.width).roundToInt().coerceAtLeast(1)
        Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }
}
