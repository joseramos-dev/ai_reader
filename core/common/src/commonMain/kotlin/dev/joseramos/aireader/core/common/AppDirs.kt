package dev.joseramos.aireader.core.common

import java.io.File

/**
 * Carpetas de la app: [files] guarda lo que no se puede perder (libros importados, portadas, modelos) y
 * [cache] lo que se puede regenerar. Cada plataforma las decide al arrancar: `filesDir` y `cacheDir` en
 * Android, las carpetas del usuario en Windows.
 */
data class AppDirs(val files: File, val cache: File)
