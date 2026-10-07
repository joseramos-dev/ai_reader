package dev.joseramos.aireader.ai.models

import java.io.InputStream

/**
 * Ficheros que la app trae empaquetados: los assets del APK en Android y los recursos del instalador en
 * Windows. [path] es relativo (`bundled_models/<modelo>/<fichero>`); devuelve `null` si no viene empaquetado.
 */
fun interface BundledFiles {
    fun open(path: String): InputStream?
}
