package dev.joseramos.aireader.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colores semánticos de la app (docs/02-diseno-tecnico.md §3.2).
 *
 * El acento naranja no tiene contraste suficiente para texto blanco encima ni para
 * texto naranja sobre fondo claro, así que hay dos variantes: [accent] para rellenos e
 * iconos y [accentText] para texto y enlaces; sobre el acento se escribe en [onAccent].
 */
@Immutable
data class AppColors(
    val background: Color,
    val groupedBackground: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val fill: Color,
    val accent: Color,
    val accentText: Color,
    val onAccent: Color,
    val accentFill: Color,
    val highlight: Color,
    val destructive: Color,
    val barBackground: Color,
    val isDark: Boolean
)

internal val LightColors = AppColors(
    background = Color(0xFFFFFFFF),
    groupedBackground = Color(0xFFF2F2F7),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFFFFFFF),
    label = Color(0xFF000000),
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43),
    fill = Color(0x1F787880),
    accent = Color(0xFFFF9500),
    accentText = Color(0xFFB35900),
    onAccent = Color(0xFF1C1C1E),
    accentFill = Color(0x26FF9500),
    highlight = Color(0x2EFF9500),
    destructive = Color(0xFFD70015),
    barBackground = Color(0xCCF9F9F9),
    isDark = false
)

internal val DarkColors = AppColors(
    background = Color(0xFF000000),
    groupedBackground = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    surfaceElevated = Color(0xFF2C2C2E),
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0x99545458),
    fill = Color(0x3D787880),
    accent = Color(0xFFFF9F0A),
    accentText = Color(0xFFFF9F0A),
    onAccent = Color(0xFF1C1C1E),
    accentFill = Color(0x33FF9F0A),
    highlight = Color(0x4DFF9F0A),
    destructive = Color(0xFFFF453A),
    barBackground = Color(0xCC161616),
    isDark = true
)
