package dev.joseramos.aireader.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.joseramos.aireader.core.data.settings.ThemeMode
import dev.joseramos.aireader.core.designsystem.theme.AiReaderTheme

/**
 * Raíz de la interfaz, igual en Android y en Windows: el tema elegido (claro, oscuro o el del sistema) y la
 * navegación. [onDarkTheme] avisa del tema en uso, para que la plataforma ajuste lo suyo (las barras del sistema).
 */
@Composable
fun AiReaderRoot(viewModel: MainViewModel, onDarkTheme: @Composable (dark: Boolean) -> Unit = {}) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    onDarkTheme(dark)
    AiReaderTheme(darkTheme = dark) {
        AiReaderApp(openBookRequests = viewModel.openBookRequests)
    }
}
