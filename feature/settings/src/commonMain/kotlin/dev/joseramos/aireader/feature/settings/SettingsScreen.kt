package dev.joseramos.aireader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.joseramos.aireader.ai.models.ModelCatalog
import dev.joseramos.aireader.ai.models.ModelState
import dev.joseramos.aireader.core.common.AppInfo
import dev.joseramos.aireader.core.data.settings.AppSettings
import dev.joseramos.aireader.core.data.settings.BudgetLevel
import dev.joseramos.aireader.core.data.settings.DailyUsage
import dev.joseramos.aireader.core.data.settings.ThemeMode
import dev.joseramos.aireader.core.designsystem.component.AiUsageRow
import dev.joseramos.aireader.core.designsystem.component.ApiKeySheet
import dev.joseramos.aireader.core.designsystem.component.AppBottomSheet
import dev.joseramos.aireader.core.designsystem.component.AppSwitch
import dev.joseramos.aireader.core.designsystem.component.Cell
import dev.joseramos.aireader.core.designsystem.component.GroupedSection
import dev.joseramos.aireader.core.designsystem.component.GroupedSectionDefaults
import dev.joseramos.aireader.core.designsystem.component.LargeTitleScaffold
import dev.joseramos.aireader.core.designsystem.component.PlainButton
import dev.joseramos.aireader.core.designsystem.component.formatTokens
import dev.joseramos.aireader.core.designsystem.platform.rememberFilePicker
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import dev.joseramos.aireader.feature.settings.generated.resources.Res
import dev.joseramos.aireader.feature.settings.generated.resources.settings_about
import dev.joseramos.aireader.feature.settings.generated.resources.settings_ai
import dev.joseramos.aireader.feature.settings.generated.resources.settings_ai_footer
import dev.joseramos.aireader.feature.settings.generated.resources.settings_ai_usage
import dev.joseramos.aireader.feature.settings.generated.resources.settings_ai_usage_footer
import dev.joseramos.aireader.feature.settings.generated.resources.settings_analysis_model
import dev.joseramos.aireader.feature.settings.generated.resources.settings_api_key
import dev.joseramos.aireader.feature.settings.generated.resources.settings_api_key_set
import dev.joseramos.aireader.feature.settings.generated.resources.settings_api_key_unset
import dev.joseramos.aireader.feature.settings.generated.resources.settings_appearance
import dev.joseramos.aireader.feature.settings.generated.resources.settings_cancel
import dev.joseramos.aireader.feature.settings.generated.resources.settings_characters
import dev.joseramos.aireader.feature.settings.generated.resources.settings_characters_auto
import dev.joseramos.aireader.feature.settings.generated.resources.settings_characters_manual
import dev.joseramos.aireader.feature.settings.generated.resources.settings_daily_budget
import dev.joseramos.aireader.feature.settings.generated.resources.settings_delete
import dev.joseramos.aireader.feature.settings.generated.resources.settings_download
import dev.joseramos.aireader.feature.settings.generated.resources.settings_model_installed
import dev.joseramos.aireader.feature.settings.generated.resources.settings_model_not_installed
import dev.joseramos.aireader.feature.settings.generated.resources.settings_rag_eval
import dev.joseramos.aireader.feature.settings.generated.resources.settings_rag_eval_hint
import dev.joseramos.aireader.feature.settings.generated.resources.settings_reading
import dev.joseramos.aireader.feature.settings.generated.resources.settings_retry
import dev.joseramos.aireader.feature.settings.generated.resources.settings_speed
import dev.joseramos.aireader.feature.settings.generated.resources.settings_speed_value
import dev.joseramos.aireader.feature.settings.generated.resources.settings_theme
import dev.joseramos.aireader.feature.settings.generated.resources.settings_theme_dark
import dev.joseramos.aireader.feature.settings.generated.resources.settings_theme_light
import dev.joseramos.aireader.feature.settings.generated.resources.settings_theme_system
import dev.joseramos.aireader.feature.settings.generated.resources.settings_title
import dev.joseramos.aireader.feature.settings.generated.resources.settings_tokens
import dev.joseramos.aireader.feature.settings.generated.resources.settings_usage
import dev.joseramos.aireader.feature.settings.generated.resources.settings_usage_detail
import dev.joseramos.aireader.feature.settings.generated.resources.settings_usage_reset
import dev.joseramos.aireader.feature.settings.generated.resources.settings_version
import dev.joseramos.aireader.feature.settings.generated.resources.settings_voice_downloading
import dev.joseramos.aireader.feature.settings.generated.resources.settings_voice_failed
import dev.joseramos.aireader.feature.settings.generated.resources.settings_voice_installing
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Serializable
data object SettingsRoute

fun NavGraphBuilder.settingsScreen() {
    composable<SettingsRoute> {
        val viewModel: SettingsViewModel = koinViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val ragEval by viewModel.ragEval.collectAsStateWithLifecycle()
        SettingsScreen(state, viewModel)
        ragEval?.let { RagEvalSheet(it, viewModel::dismissRagEvaluation) }
    }
}

private enum class Sheet {
    THEME,
    SPEED,
    API_KEY,
    ANALYSIS_MODEL,
    DAILY_BUDGET
}

/** Modelos de Gemini que se pueden elegir, con su nombre visible. */
private val geminiModels = listOf(
    "gemini-3.8-flash" to "Gemini 3.8 Flash",
    "gemini-3.1-flash-lite" to "Gemini 3.1 Flash-Lite",
    "gemini-3.1-pro-preview" to "Gemini 3.1 Pro (vista previa)"
)

private fun modelName(id: String) = geminiModels.firstOrNull { it.first == id }?.second ?: id

private const val MB = 1_000_000

@Composable
private fun SettingsScreen(state: SettingsUiState, viewModel: SettingsViewModel) {
    var sheet by rememberSaveable { mutableStateOf<Sheet?>(null) }
    val settings = state.settings
    val appInfo: AppInfo = koinInject()
    val version = appInfo.version
    val debuggable = appInfo.debuggable
    val evalPicker = rememberFilePicker(listOf("application/json", "text/plain")) { viewModel.runRagEvaluation(it) }

    LargeTitleScaffold(title = stringResource(Res.string.settings_title), grouped = true) {
        item(key = "appearance") {
            GroupedSection(
                header = stringResource(Res.string.settings_appearance),
                dividerInset = GroupedSectionDefaults.IconDividerInset
            ) {
                row {
                    Cell(
                        title = stringResource(Res.string.settings_theme),
                        icon = Icons.Outlined.Contrast,
                        iconBackground = Color(0xFF5E5CE6),
                        value = themeLabel(settings.themeMode),
                        showChevron = true,
                        onClick = { sheet = Sheet.THEME }
                    )
                }
            }
        }
        item(key = "reading") {
            GroupedSection(
                header = stringResource(Res.string.settings_reading),
                dividerInset = GroupedSectionDefaults.IconDividerInset
            ) {
                row {
                    Cell(
                        title = stringResource(Res.string.settings_speed),
                        icon = Icons.Outlined.Speed,
                        value = stringResource(Res.string.settings_speed_value, formatSpeed(settings.readingSpeed)),
                        showChevron = true,
                        onClick = { sheet = Sheet.SPEED }
                    )
                }
            }
        }
        item(key = "ai") {
            GroupedSection(
                header = stringResource(Res.string.settings_ai),
                dividerInset = GroupedSectionDefaults.IconDividerInset,
                footer = stringResource(Res.string.settings_ai_footer)
            ) {
                row {
                    Cell(
                        title = stringResource(Res.string.settings_api_key),
                        icon = Icons.Outlined.Key,
                        iconBackground = Color(0xFF8E8E93),
                        value = stringResource(
                            if (state.hasApiKey) Res.string.settings_api_key_set else Res.string.settings_api_key_unset
                        ),
                        showChevron = true,
                        onClick = { sheet = Sheet.API_KEY }
                    )
                }
                row {
                    Cell(
                        title = stringResource(Res.string.settings_analysis_model),
                        icon = Icons.Outlined.Summarize,
                        iconBackground = Color(0xFF34C759),
                        value = modelName(settings.analysisModel),
                        showChevron = true,
                        onClick = { sheet = Sheet.ANALYSIS_MODEL }
                    )
                }
                row { SearchModelCell(state.searchModel, viewModel) }
                row {
                    Cell(
                        title = stringResource(Res.string.settings_characters),
                        subtitle = stringResource(
                            if (settings.autoCharacterAnalysis) {
                                Res.string.settings_characters_auto
                            } else {
                                Res.string.settings_characters_manual
                            }
                        ),
                        icon = Icons.Outlined.People,
                        iconBackground = Color(0xFFFF9500),
                        trailing = { AppSwitch(settings.autoCharacterAnalysis, viewModel::setAutoCharacterAnalysis) }
                    )
                }
                row {
                    Cell(
                        title = stringResource(Res.string.settings_usage),
                        subtitle = stringResource(
                            Res.string.settings_usage_detail,
                            formatTokens(state.usage.inputTokens),
                            formatTokens(state.usage.outputTokens)
                        ),
                        icon = Icons.Outlined.DataUsage,
                        iconBackground = Color(0xFF8E8E93),
                        trailing = {
                            PlainButton(stringResource(Res.string.settings_usage_reset), viewModel::resetUsage)
                        }
                    )
                }
            }
        }
        item(key = "ai-usage") {
            GroupedSection(
                header = stringResource(Res.string.settings_ai_usage),
                footer = stringResource(Res.string.settings_ai_usage_footer)
            ) {
                row {
                    AiUsageRow(
                        remaining = state.today.remaining,
                        usedTokens = state.today.tokens,
                        budgetTokens = state.today.budget,
                        exhausted = state.today.exhausted,
                        resetsAt = state.today.resetsAt,
                        modifier = Modifier.padding(vertical = Spacing.xxs),
                        overBudget = state.today.level == BudgetLevel.OVER
                    )
                }
                row {
                    Cell(
                        title = stringResource(Res.string.settings_daily_budget),
                        value = formatTokens(state.today.budget),
                        showChevron = true,
                        onClick = { sheet = Sheet.DAILY_BUDGET }
                    )
                }
            }
        }
        item(key = "about") {
            GroupedSection(
                header = stringResource(Res.string.settings_about),
                dividerInset = GroupedSectionDefaults.IconDividerInset
            ) {
                row {
                    Cell(
                        title = stringResource(Res.string.settings_version),
                        icon = Icons.Outlined.Info,
                        iconBackground = Color(0xFF8E8E93),
                        value = version
                    )
                }
                // Herramienta de desarrollo: solo en compilaciones de depuración.
                if (debuggable) {
                    row {
                        Cell(
                            title = stringResource(Res.string.settings_rag_eval),
                            subtitle = stringResource(Res.string.settings_rag_eval_hint),
                            icon = Icons.Outlined.Science,
                            iconBackground = Color(0xFF8E8E93),
                            showChevron = true,
                            onClick = { evalPicker.launch() }
                        )
                    }
                }
            }
        }
    }

    when (sheet) {
        Sheet.THEME -> OptionsSheet(
            title = stringResource(Res.string.settings_theme),
            options = ThemeMode.entries.map { it to themeLabel(it) },
            selected = settings.themeMode,
            onSelect = viewModel::setTheme,
            onDismiss = { sheet = null }
        )
        Sheet.ANALYSIS_MODEL -> OptionsSheet(
            title = stringResource(Res.string.settings_analysis_model),
            options = geminiModels,
            selected = settings.analysisModel,
            onSelect = viewModel::setAnalysisModel,
            onDismiss = { sheet = null }
        )
        Sheet.DAILY_BUDGET -> OptionsSheet(
            title = stringResource(Res.string.settings_daily_budget),
            options = DailyUsage.BUDGET_OPTIONS.map {
                it to stringResource(Res.string.settings_tokens, formatTokens(it))
            },
            selected = state.today.budget,
            onSelect = viewModel::setDailyBudget,
            onDismiss = { sheet = null }
        )
        Sheet.SPEED -> SpeedSheet(settings.readingSpeed, viewModel::setSpeed, onDismiss = { sheet = null })
        Sheet.API_KEY -> ApiKeySheet(
            onSave = viewModel::saveApiKey,
            onDismiss = { sheet = null },
            onRemove = if (state.hasApiKey) viewModel::removeApiKey else null
        )
        null -> Unit
    }
}

/** Modelo de embeddings del chat: se descarga una vez (≈135 MB) y funciona sin conexión. */
@Composable
private fun SearchModelCell(model: ModelState, viewModel: SettingsViewModel) {
    val info = ModelCatalog.e5Small
    val subtitle = when (model) {
        ModelState.NotInstalled -> stringResource(
            Res.string.settings_model_not_installed,
            (info.sizeBytes / MB).toInt()
        )
        is ModelState.Downloading -> stringResource(
            Res.string.settings_voice_downloading,
            (model.progress * 100).toInt()
        )
        ModelState.Installing -> stringResource(Res.string.settings_voice_installing)
        is ModelState.Installed -> stringResource(Res.string.settings_model_installed, (model.sizeBytes / MB).toInt())
        is ModelState.Failed -> stringResource(Res.string.settings_voice_failed, model.message)
    }
    Cell(
        title = info.displayName,
        subtitle = subtitle,
        icon = Icons.AutoMirrored.Outlined.ManageSearch,
        iconBackground = Color(0xFF30B0C7),
        trailing = {
            when (model) {
                ModelState.NotInstalled -> PlainButton(
                    stringResource(Res.string.settings_download),
                    viewModel::downloadSearchModel
                )
                is ModelState.Failed -> PlainButton(
                    stringResource(Res.string.settings_retry),
                    viewModel::downloadSearchModel
                )
                is ModelState.Downloading -> PlainButton(
                    stringResource(Res.string.settings_cancel),
                    viewModel::cancelSearchModelDownload
                )
                is ModelState.Installed -> PlainButton(
                    stringResource(Res.string.settings_delete),
                    viewModel::deleteSearchModel
                )
                ModelState.Installing -> Unit
            }
        }
    )
}

@Composable
private fun <T> OptionsSheet(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AppBottomSheet(onDismissRequest = onDismiss, title = title) {
        GroupedSection {
            options.forEach { (value, label) ->
                row {
                    Cell(
                        title = label,
                        onClick = {
                            onSelect(value)
                            onDismiss()
                        },
                        trailing = {
                            if (value == selected) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = AppTheme.colors.accentText,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedSheet(current: Float, onChange: (Float) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableFloatStateOf(current) }
    AppBottomSheet(onDismissRequest = onDismiss, title = stringResource(Res.string.settings_speed)) {
        Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                stringResource(Res.string.settings_speed_value, formatSpeed(value)),
                style = AppTheme.typography.title,
                color = AppTheme.colors.label
            )
            Slider(
                value = value,
                onValueChange = { value = (it * 4).let(Math::round) / 4f },
                onValueChangeFinished = { onChange(value) },
                valueRange = AppSettings.MIN_SPEED..AppSettings.MAX_SPEED,
                steps = 4,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AppTheme.colors.accent)
            )
        }
    }
}

@Composable
private fun themeLabel(mode: ThemeMode) = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> Res.string.settings_theme_system
        ThemeMode.LIGHT -> Res.string.settings_theme_light
        ThemeMode.DARK -> Res.string.settings_theme_dark
    }
)

private fun formatSpeed(speed: Float) = "%.2f".format(speed).trimEnd('0').trimEnd('.', ',').replace('.', ',')
