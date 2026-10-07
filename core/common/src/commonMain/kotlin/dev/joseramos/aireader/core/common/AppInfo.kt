package dev.joseramos.aireader.core.common

/** Datos de la app que muestran los ajustes: su [version] y si es una compilación de depuración ([debuggable]). */
data class AppInfo(val version: String, val debuggable: Boolean)
