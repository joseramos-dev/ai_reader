package dev.joseramos.aireader.indexing

/**
 * Programa la indexación de un libro como trabajo único: en Android lo conserva WorkManager aunque se cierre la
 * app; en Windows corre en la propia app. En ambos casos es [IndexRunner] quien indexa, y continúa donde se quedó.
 */
interface IndexScheduler {
    /** Con [replace], un trabajo ya pendiente o en marcha se sustituye; si no, se deja como está. */
    fun enqueue(bookId: String, replace: Boolean = false)

    fun cancel(bookId: String)
}
