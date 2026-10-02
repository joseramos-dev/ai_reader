package dev.joseramos.aireader.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.github.takahirom.roborazzi.captureRoboImage
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.joseramos.aireader.core.designsystem.component.AppSwitch
import dev.joseramos.aireader.core.designsystem.component.BarIconButton
import dev.joseramos.aireader.core.designsystem.component.Cell
import dev.joseramos.aireader.core.designsystem.component.EmptyState
import dev.joseramos.aireader.core.designsystem.component.GroupedSection
import dev.joseramos.aireader.core.designsystem.component.GroupedSectionDefaults
import dev.joseramos.aireader.core.designsystem.component.LargeTitleScaffold
import dev.joseramos.aireader.core.designsystem.component.PlainButton
import dev.joseramos.aireader.core.designsystem.component.PrimaryButton
import dev.joseramos.aireader.core.designsystem.component.SecondaryButton
import dev.joseramos.aireader.core.designsystem.component.TabBar
import dev.joseramos.aireader.core.designsystem.component.TabItem
import dev.joseramos.aireader.core.designsystem.theme.AiReaderTheme
import dev.joseramos.aireader.core.designsystem.theme.BarSize
import dev.joseramos.aireader.core.designsystem.theme.LocalBottomBarHeight
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Capturas de referencia del sistema de diseño en claro y oscuro, renderizadas en la JVM.
 * Se generan con `./gradlew :core:designsystem:recordRoborazziDebug` en `build/outputs/roborazzi`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ComponentScreenshotTest {

    @Test
    fun settingsLight() = capture("settings_light", dark = false) { SettingsGallery() }

    @Test
    fun settingsDark() = capture("settings_dark", dark = true) { SettingsGallery() }

    @Test
    fun emptyLibraryLight() = capture("library_empty_light", dark = false) { EmptyLibrary() }

    @Test
    fun emptyLibraryDark() = capture("library_empty_dark", dark = true) { EmptyLibrary() }

    @Test
    fun buttonsLight() = capture("buttons_light", dark = false) { Buttons() }

    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) =
        captureRoboImage("build/outputs/roborazzi/$name.png") {
            AiReaderTheme(darkTheme = dark) { WithTabBar(content) }
        }
}

@Composable
private fun WithTabBar(content: @Composable () -> Unit) {
    val haze = rememberHazeState()
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalBottomBarHeight provides BarSize.tabBar) {
            Box(Modifier.fillMaxSize().hazeSource(haze)) { content() }
        }
        TabBar(
            items = listOf(
                TabItem("Biblioteca", Icons.Outlined.AutoStories, Icons.Filled.AutoStories),
                TabItem("Ajustes", Icons.Outlined.Settings, Icons.Filled.Settings)
            ),
            selectedIndex = 1,
            onSelect = {},
            hazeState = haze,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun SettingsGallery() {
    LargeTitleScaffold(title = "Ajustes", grouped = true) {
        item {
            GroupedSection(header = "Apariencia", dividerInset = GroupedSectionDefaults.IconDividerInset) {
                row {
                    Cell(
                        "Tema",
                        icon = Icons.Outlined.Contrast,
                        iconBackground = Color(
                            0xFF5E5CE6
                        ),
                        value = "Sistema",
                        showChevron = true,
                        onClick = {
                        }
                    )
                }
            }
        }
        item {
            GroupedSection(header = "Lectura en voz alta", dividerInset = GroupedSectionDefaults.IconDividerInset) {
                row {
                    Cell("Velocidad", icon = Icons.Outlined.Speed, value = "1,25×", showChevron = true, onClick = {})
                }
                row {
                    Cell(
                        "Voz en español",
                        subtitle = "Piper · Descargada · 63 MB",
                        icon = Icons.Outlined.RecordVoiceOver,
                        iconBackground = Color(0xFFFF2D55),
                        trailing = { PlainButton("Borrar", {}) }
                    )
                }
                row {
                    Cell("Resaltar la frase", icon = Icons.Outlined.Speed, trailing = {
                        AppSwitch(checked = true, onCheckedChange = {})
                    })
                }
            }
        }
        item {
            GroupedSection(
                header = "Inteligencia artificial",
                dividerInset = GroupedSectionDefaults.IconDividerInset,
                footer = "Las preguntas y los fragmentos del libro necesarios para responder se envían a Google."
            ) {
                row {
                    Cell(
                        "Clave de API",
                        icon = Icons.Outlined.Key,
                        iconBackground = Color(
                            0xFF8E8E93
                        ),
                        value = "Configurada",
                        showChevron = true,
                        onClick = {
                        }
                    )
                }
                row {
                    Cell(
                        "Modelo del chat",
                        icon = Icons.Outlined.AutoAwesome,
                        iconBackground = Color(
                            0xFF30B0C7
                        ),
                        value = "Gemini 3.8 Flash",
                        showChevron = true,
                        onClick = {
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary() {
    LargeTitleScaffold(title = "Biblioteca") {
        item {
            EmptyState(
                icon = Icons.Outlined.AutoStories,
                title = "Tu biblioteca está vacía",
                message = "Importa un PDF para leerlo, escucharlo y hacerle preguntas.",
                actionLabel = "Importar PDF",
                onAction = {},
                modifier = Modifier.padding(top = Spacing.xxl * 3)
            )
        }
    }
}

@Composable
private fun Buttons() {
    LargeTitleScaffold(
        title = "Botones",
        navigationAction = { BarIconButton(Icons.AutoMirrored.Rounded.ArrowBackIos, "Volver", {}) }
    ) {
        item {
            Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                PrimaryButton("Escuchar desde aquí", {}, Modifier.fillMaxWidth())
                SecondaryButton("Resumir capítulo", {}, Modifier.fillMaxWidth(), icon = Icons.Outlined.AutoAwesome)
                PlainButton("Cancelar", {})
                PrimaryButton("Desactivado", {}, Modifier.fillMaxWidth(), enabled = false)
            }
        }
    }
}
