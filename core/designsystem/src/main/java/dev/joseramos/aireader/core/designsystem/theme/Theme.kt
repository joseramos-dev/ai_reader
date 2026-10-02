package dev.joseramos.aireader.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalAppColors = staticCompositionLocalOf { LightColors }
private val LocalAppTypography = staticCompositionLocalOf { DefaultTypography }

/** Acceso a los tokens: `AppTheme.colors.accent`, `AppTheme.typography.body`. */
object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable
        get() = LocalAppColors.current

    val typography: AppTypography
        @Composable @ReadOnlyComposable
        get() = LocalAppTypography.current
}

/**
 * Tema de la app. Expone los tokens propios y además configura Material 3 con ellos, para
 * que los componentes de Material que se usen (interruptores, campos de texto, hojas)
 * hereden los mismos colores y la tipografía Inter.
 */
@Composable
fun AiReaderTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    val typography = DefaultTypography
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            onBackground = colors.label,
            surface = colors.surface,
            onSurface = colors.label,
            surfaceVariant = colors.surfaceElevated,
            onSurfaceVariant = colors.secondaryLabel,
            surfaceContainerLow = colors.surfaceElevated,
            outline = colors.separator,
            outlineVariant = colors.separator,
            error = colors.destructive
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            onBackground = colors.label,
            surface = colors.surface,
            onSurface = colors.label,
            surfaceVariant = colors.groupedBackground,
            onSurfaceVariant = colors.secondaryLabel,
            surfaceContainerLow = colors.surfaceElevated,
            outline = colors.separator,
            outlineVariant = colors.separator,
            error = colors.destructive
        )
    }
    val material = Typography(
        displaySmall = typography.largeTitle,
        headlineSmall = typography.title,
        titleMedium = typography.headline,
        bodyLarge = typography.body,
        bodyMedium = typography.subheadline,
        bodySmall = typography.footnote,
        labelLarge = typography.headline,
        labelMedium = typography.footnote,
        labelSmall = typography.caption
    )
    CompositionLocalProvider(LocalAppColors provides colors, LocalAppTypography provides typography) {
        MaterialTheme(colorScheme = scheme, typography = material, content = content)
    }
}
