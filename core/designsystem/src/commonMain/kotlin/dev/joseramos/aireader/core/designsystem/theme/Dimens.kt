package dev.joseramos.aireader.core.designsystem.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Escala de espaciado de 4 dp. */
object Spacing {
    val xxs = 4.dp
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val l = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object Radius {
    val cell = 10.dp
    val button = 12.dp
    val card = 14.dp
    val sheet = 20.dp
}

/** Tamaños fijos de las barras, compartidos por el scaffold y la barra de pestañas. */
object BarSize {
    val topBar = 44.dp
    val tabBar = 50.dp
    val minTouch = 44.dp
}

/**
 * Alto que ocupa la barra de pestañas superpuesta (sin contar la barra de navegación del
 * sistema). Las pantallas lo usan como relleno inferior para que el contenido no quede debajo.
 */
val LocalBottomBarHeight = compositionLocalOf<Dp> { 0.dp }
