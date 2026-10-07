package dev.joseramos.aireader.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.joseramos.aireader.core.data.settings.ThemeMode
import dev.joseramos.aireader.core.designsystem.theme.AiReaderTheme
import dev.joseramos.aireader.core.designsystem.theme.AppTheme

/**
 * Raíz de la interfaz, igual en Android y en Windows: el tema elegido (claro, oscuro o el del sistema) y la
 * navegación. [onDarkTheme] avisa del tema en uso, para que la plataforma ajuste lo suyo (las barras del sistema).
 * [maxContentWidth] limita el ancho de la app y la centra: el diseño es de móvil y en una ventana ancha se
 * estiraría; en Android se deja sin límite.
 */
@Composable
fun AiReaderRoot(
    viewModel: MainViewModel,
    maxContentWidth: Dp = Dp.Unspecified,
    onDarkTheme: @Composable (dark: Boolean) -> Unit = {}
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    onDarkTheme(dark)
    AiReaderTheme(darkTheme = dark) {
        Box(Modifier.fillMaxSize().background(AppTheme.colors.groupedBackground), Alignment.Center) {
            Box(Modifier.fillMaxHeight().widthIn(max = maxContentWidth)) {
                AiReaderApp(openBookRequests = viewModel.openBookRequests)
            }
        }
    }
}
