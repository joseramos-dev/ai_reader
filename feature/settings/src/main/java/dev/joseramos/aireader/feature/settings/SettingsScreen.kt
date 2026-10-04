package dev.joseramos.aireader.feature.settings

import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.RecordVoiceOver
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.joseramos.aireader.ai.models.ModelCatalog
import dev.joseramos.aireader.core.designsystem.R as DesignSystemR
import dev.joseramos.aireader.ai.models.ModelState
import dev.joseramos.aireader.core.data.settings.AppSettings
import dev.joseramos.aireader.core.data.settings.BudgetLevel
import dev.joseramos.aireader.core.data.settings.CloudVoiceTier
import dev.joseramos.aireader.core.data.settings.DailyUsage
import dev.joseramos.aireader.core.data.settings.ThemeMode
import dev.joseramos.aireader.core.data.settings.TtsEngineKind
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
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import dev.joseramos.aireader.text.Language
import dev.joseramos.aireader.tts.SystemVoiceSettings
import dev.joseramos.aireader.tts.VoiceAvailability
import kotlinx.serialization.Serializable

@Serializable
data object SettingsRoute

fun NavGraphBuilder.settingsScreen() {
    composable<SettingsRoute> {
        val viewModel: SettingsViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val ragEval by viewModel.ragEval.collectAsStateWithLifecycle()
        // Al entrar y al volver de los ajustes del sistema (por ejemplo, tras instalar una voz).
        LifecycleResumeEffect(Unit) {
            viewModel.refreshVoices()
            onPauseOrDispose {}
        }
        SettingsScreen(state, viewModel)
        ragEval?.let { RagEvalSheet(it, viewModel::dismissRagEvaluation) }
    }
}

private enum class Sheet {
    THEME, SPEED, API_KEY, CHAT_MODEL, SUMMARY_MODEL, DAILY_BUDGET, TTS_ENGINE, CLOUD_VOICE_TIER, CLOUD_TTS_API_KEY
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
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    val debuggable = remember { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
    val evalPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(viewModel::runRagEvaluation)
    }

    LargeTitleScaffold(title = stringResource(R.string.settings_title), grouped = true) {
        item(key = "appearance") {
            GroupedSection(
                header = stringResource(R.string.settings_appearance),
                dividerInset = GroupedSectionDefaults.IconDividerInset
            ) {
                row {
                    Cell(
                        title = stringResource(R.string.settings_theme),
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
                header = stringResource(R.string.settings_reading),
                dividerInset = GroupedSectionDefaults.IconDividerInset,
                footer = stringResource(R.string.settings_reading_footer)
                    .takeIf { settings.ttsEngine == TtsEngineKind.GOOGLE_CLOUD }
            ) {
                row {
                    Cell(
                        title = stringResource(R.string.settings_speed),
                        icon = Icons.Outlined.Speed,
                        value = stringResource(R.string.settings_speed_value, formatSpeed(settings.readingSpeed)),
                        showChevron = true,
                        onClick = { sheet = Sheet.SPEED }
                    )
                }
                row {
                    Cell(
                        title = stringResource(R.string.settings_tts_engine),
                        icon = Icons.Outlined.Cloud,
                        iconBackground = Color(0xFF007AFF),
                        value = ttsEngineLabel(settings.ttsEngine),
                        showChevron = true,
                        onClick = { sheet = Sheet.TTS_ENGINE }
                    )
                }
                if (settings.ttsEngine == TtsEngineKind.GOOGLE_CLOUD) {
                    row {
                        Cell(
                            title = stringResource(R.string.settings_cloud_voice_tier),
                            icon = Icons.Outlined.GraphicEq,
                            iconBackground = Color(0xFFFF9500),
                            value = cloudVoiceTierLabel(settings.cloudVoiceTier),
                            showChevron = true,
                            onClick = { sheet = Sheet.CLOUD_VOICE_TIER }
                        )
                    }
                    row {
                        Cell(
                            title = stringResource(R.string.settings_cloud_tts_api_key),
                            icon = Icons.Outlined.Key,
                            iconBackground = Color(0xFF8E8E93),
                            value = stringResource(
                                if (state.hasCloudTtsApiKey) {
                                    R.string.settings_api_key_set
                                } else {
                                    R.string.settings_api_key_unset
                                }
                            ),
                            showChevron = true,
                            onClick = { sheet = Sheet.CLOUD_TTS_API_KEY }
                        )
                    }
                }
                Language.entries.forEach { language ->
                    row {
                        VoiceCell(language, state.voices[language], viewModel) { sheet = Sheet.CLOUD_TTS_API_KEY }
                    }
                }
            }
        }
        item(key = "ai") {
            GroupedSection(
                header = stringResource(R.string.settings_ai),
                dividerInset = GroupedSectionDefaults.IconDividerInset,
                footer = stringResource(R.string.settings_ai_footer)
            ) {
                row {
                    Cell(
                        title = stringResource(R.string.settings_api_key),
                        icon = Icons.Outlined.Key,
                        iconBackground = Color(0xFF8E8E93),
                        value = stringResource(
                            if (state.hasApiKey) R.string.settings_api_key_set else R.string.settings_api_key_unset
                        ),
                        showChevron = true,
                        onClick = { sheet = Sheet.API_KEY }
                    )
                }
                row {
                    Cell(
                        title = stringResource(R.string.settings_chat_model),
                        icon = Icons.Outlined.AutoAwesome,
                        iconBackground = Color(0xFF30B0C7),
                        value = modelName(settings.chatModel),
                        showChevron = true,
                        onClick = { sheet = Sheet.CHAT_MODEL }
                    )
                }
                row {
                    Cell(
                        title = stringResource(R.string.settings_summary_model),
                        icon = Icons.Outlined.Summarize,
                        iconBackground = Color(0xFF34C759),
                        value = modelName(settings.summaryModel),
                        showChevron = true,
                        onClick = { sheet = Sheet.SUMMARY_MODEL }
                    )
                }
                row { SearchModelCell(state.searchModel, viewModel) }
                row {
                    Cell(
                        title = stringResource(R.string.settings_characters),
                        subtitle = stringResource(
                            if (settings.autoCharacterAnalysis) {
                                R.string.settings_characters_auto
                            } else {
                                R.string.settings_characters_manual
                            }
                        ),
                        icon = Icons.Outlined.People,
                        iconBackground = Color(0xFFFF9500),
                        trailing = { AppSwitch(settings.autoCharacterAnalysis, viewModel::setAutoCharacterAnalysis) }
                    )
                }
                row {
                    Cell(
                        title = stringResource(R.string.settings_usage),
                        subtitle = stringResource(
                            R.string.settings_usage_detail,
                            formatTokens(state.usage.inputTokens),
                            formatTokens(state.usage.outputTokens)
                        ),
                        icon = Icons.Outlined.DataUsage,
                        iconBackground = Color(0xFF8E8E93),
                        trailing = { PlainButton(stringResource(R.string.settings_usage_reset), viewModel::resetUsage) }
                    )
                }
            }
        }
        item(key = "ai-usage") {
            GroupedSection(
                header = stringResource(R.string.settings_ai_usage),
                footer = stringResource(R.string.settings_ai_usage_footer)
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
                        title = stringResource(R.string.settings_daily_budget),
                        value = formatTokens(state.today.budget),
                        showChevron = true,
                        onClick = { sheet = Sheet.DAILY_BUDGET }
                    )
                }
            }
        }
        item(key = "about") {
            GroupedSection(
                header = stringResource(R.string.settings_about),
                dividerInset = GroupedSectionDefaults.IconDividerInset
            ) {
                row {
                    Cell(
                        title = stringResource(R.string.settings_version),
                        icon = Icons.Outlined.Info,
                        iconBackground = Color(0xFF8E8E93),
                        value = version
                    )
                }
                // Herramienta de desarrollo: solo en compilaciones de depuración.
                if (debuggable) {
                    row {
                        Cell(
                            title = stringResource(R.string.settings_rag_eval),
                            subtitle = stringResource(R.string.settings_rag_eval_hint),
                            icon = Icons.Outlined.Science,
                            iconBackground = Color(0xFF8E8E93),
                            showChevron = true,
                            onClick = { evalPicker.launch(arrayOf("application/json", "text/plain", "*/*")) }
                        )
                    }
                }
            }
        }
    }

    when (sheet) {
        Sheet.THEME -> OptionsSheet(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries.map { it to themeLabel(it) },
            selected = settings.themeMode,
            onSelect = viewModel::setTheme,
            onDismiss = { sheet = null }
        )
        Sheet.CHAT_MODEL -> OptionsSheet(
            title = stringResource(R.string.settings_chat_model),
            options = geminiModels,
            selected = settings.chatModel,
            onSelect = viewModel::setChatModel,
            onDismiss = { sheet = null }
        )
        Sheet.SUMMARY_MODEL -> OptionsSheet(
            title = stringResource(R.string.settings_summary_model),
            options = geminiModels,
            selected = settings.summaryModel,
            onSelect = viewModel::setSummaryModel,
            onDismiss = { sheet = null }
        )
        Sheet.DAILY_BUDGET -> OptionsSheet(
            title = stringResource(R.string.settings_daily_budget),
            options = DailyUsage.BUDGET_OPTIONS.map {
                it to stringResource(R.string.settings_tokens, formatTokens(it))
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
        Sheet.TTS_ENGINE -> OptionsSheet(
            title = stringResource(R.string.settings_tts_engine),
            options = TtsEngineKind.entries.map { it to ttsEngineLabel(it) },
            selected = settings.ttsEngine,
            onSelect = viewModel::setTtsEngine,
            onDismiss = { sheet = null }
        )
        Sheet.CLOUD_VOICE_TIER -> OptionsSheet(
            title = stringResource(R.string.settings_cloud_voice_tier),
            options = CloudVoiceTier.entries.map { it to cloudVoiceTierLabel(it) },
            selected = settings.cloudVoiceTier,
            onSelect = viewModel::setCloudVoiceTier,
            onDismiss = { sheet = null }
        )
        Sheet.CLOUD_TTS_API_KEY -> ApiKeySheet(
            onSave = viewModel::saveCloudTtsApiKey,
            onDismiss = { sheet = null },
            onRemove = if (state.hasCloudTtsApiKey) viewModel::removeCloudTtsApiKey else null,
            title = stringResource(DesignSystemR.string.cloud_tts_api_key_title),
            hint = stringResource(DesignSystemR.string.cloud_tts_api_key_hint),
            help = stringResource(DesignSystemR.string.cloud_tts_api_key_help)
        )
        null -> Unit
    }
}

/** Voz del motor elegido para un idioma: si está lista se puede probar; si no, se arregla desde aquí. */
@Composable
private fun VoiceCell(
    language: Language,
    voice: VoiceAvailability?,
    viewModel: SettingsViewModel,
    onConfigureApiKey: () -> Unit
) {
    val context = LocalContext.current
    val subtitle = stringResource(
        when (voice) {
            null -> R.string.settings_voice_checking
            VoiceAvailability.READY -> R.string.settings_voice_ready
            VoiceAvailability.MISSING -> R.string.settings_voice_missing
            VoiceAvailability.NO_ENGINE -> R.string.settings_voice_no_engine
            VoiceAvailability.NEEDS_API_KEY -> R.string.settings_voice_needs_api_key
        }
    )
    Cell(
        title = stringResource(
            if (language == Language.ENGLISH) R.string.settings_voice_english else R.string.settings_voice_spanish
        ),
        subtitle = subtitle,
        icon = Icons.Outlined.RecordVoiceOver,
        iconBackground = Color(0xFFFF2D55),
        trailing = {
            when (voice) {
                VoiceAvailability.READY -> PlainButton(
                    stringResource(R.string.settings_preview),
                    { viewModel.previewVoice(language) }
                )
                VoiceAvailability.MISSING -> PlainButton(
                    stringResource(R.string.settings_voice_install),
                    { SystemVoiceSettings.installVoice(context) }
                )
                VoiceAvailability.NO_ENGINE -> PlainButton(
                    stringResource(R.string.settings_voice_open_settings),
                    { SystemVoiceSettings.openSettings(context) }
                )
                VoiceAvailability.NEEDS_API_KEY -> PlainButton(
                    stringResource(R.string.settings_voice_configure),
                    onConfigureApiKey
                )
                null -> Unit
            }
        }
    )
}

/** Modelo de embeddings del chat: se descarga una vez (≈135 MB) y funciona sin conexión. */
@Composable
private fun SearchModelCell(model: ModelState, viewModel: SettingsViewModel) {
    val info = ModelCatalog.e5Small
    val subtitle = when (model) {
        ModelState.NotInstalled -> stringResource(R.string.settings_model_not_installed, (info.sizeBytes / MB).toInt())
        is ModelState.Downloading -> stringResource(R.string.settings_voice_downloading, (model.progress * 100).toInt())
        ModelState.Installing -> stringResource(R.string.settings_voice_installing)
        is ModelState.Installed -> stringResource(R.string.settings_model_installed, (model.sizeBytes / MB).toInt())
        is ModelState.Failed -> stringResource(R.string.settings_voice_failed, model.message)
    }
    Cell(
        title = info.displayName,
        subtitle = subtitle,
        icon = Icons.AutoMirrored.Outlined.ManageSearch,
        iconBackground = Color(0xFF30B0C7),
        trailing = {
            when (model) {
                ModelState.NotInstalled -> PlainButton(
                    stringResource(R.string.settings_download),
                    viewModel::downloadSearchModel
                )
                is ModelState.Failed -> PlainButton(
                    stringResource(R.string.settings_retry),
                    viewModel::downloadSearchModel
                )
                is ModelState.Downloading -> PlainButton(
                    stringResource(R.string.settings_cancel),
                    viewModel::cancelSearchModelDownload
                )
                is ModelState.Installed -> PlainButton(
                    stringResource(R.string.settings_delete),
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
    AppBottomSheet(onDismissRequest = onDismiss, title = stringResource(R.string.settings_speed)) {
        Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                stringResource(R.string.settings_speed_value, formatSpeed(value)),
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
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }
)

@Composable
private fun ttsEngineLabel(engine: TtsEngineKind) = stringResource(
    when (engine) {
        TtsEngineKind.SYSTEM -> R.string.settings_tts_engine_system
        TtsEngineKind.GOOGLE_CLOUD -> R.string.settings_tts_engine_google_cloud
    }
)

@Composable
private fun cloudVoiceTierLabel(tier: CloudVoiceTier) = stringResource(
    when (tier) {
        CloudVoiceTier.STANDARD -> R.string.settings_cloud_voice_tier_standard
        CloudVoiceTier.WAVENET -> R.string.settings_cloud_voice_tier_wavenet
        CloudVoiceTier.NEURAL2 -> R.string.settings_cloud_voice_tier_neural2
    }
)

private fun formatSpeed(speed: Float) = "%.2f".format(speed).trimEnd('0').trimEnd('.', ',').replace('.', ',')
