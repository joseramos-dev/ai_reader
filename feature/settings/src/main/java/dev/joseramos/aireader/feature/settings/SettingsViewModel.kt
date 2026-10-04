package dev.joseramos.aireader.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.joseramos.aireader.ai.models.ModelCatalog
import dev.joseramos.aireader.ai.models.ModelManager
import dev.joseramos.aireader.ai.models.ModelState
import dev.joseramos.aireader.ai.rag.EvalReport
import dev.joseramos.aireader.ai.rag.EvalSet
import dev.joseramos.aireader.ai.rag.RagEvaluator
import dev.joseramos.aireader.core.common.IoDispatcher
import dev.joseramos.aireader.core.data.settings.ApiUsage
import dev.joseramos.aireader.core.data.settings.AppSettings
import dev.joseramos.aireader.core.data.settings.CloudVoiceTier
import dev.joseramos.aireader.core.data.settings.DailyUsage
import dev.joseramos.aireader.core.data.settings.SecretStore
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import dev.joseramos.aireader.core.data.settings.ThemeMode
import dev.joseramos.aireader.core.data.settings.TtsEngineKind
import dev.joseramos.aireader.core.data.settings.UsageRepository
import dev.joseramos.aireader.text.Language
import dev.joseramos.aireader.tts.PlaybackController
import dev.joseramos.aireader.tts.VoiceAvailability
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/** Evaluación de la búsqueda del RAG (herramienta de desarrollo). */
sealed interface RagEvalState {
    data object Running : RagEvalState

    data class Done(val report: EvalReport) : RagEvalState

    data class Failed(val message: String) : RagEvalState
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val hasApiKey: Boolean = false,
    val hasCloudTtsApiKey: Boolean = false,
    /** Si el motor de voz elegido tiene voz para cada idioma (`null` mientras se comprueba). */
    val voices: Map<Language, VoiceAvailability> = emptyMap(),
    /** Modelo de embeddings para buscar en el libro (chat). */
    val searchModel: ModelState = ModelState.NotInstalled,
    val usage: ApiUsage = ApiUsage(),
    val today: DailyUsage = DailyUsage()
)

@HiltViewModel
@Suppress("TooManyFunctions") // Agrupa las acciones de todas las secciones de Ajustes: no gana nada partiéndola.
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ragEvaluator: RagEvaluator,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val settingsRepository: SettingsRepository,
    private val secretStore: SecretStore,
    private val modelManager: ModelManager,
    private val playback: PlaybackController,
    private val usageRepository: UsageRepository
) : ViewModel() {
    private val searchModelId = ModelCatalog.e5Small.id

    private val voices = MutableStateFlow<Map<Language, VoiceAvailability>>(emptyMap())

    val state: StateFlow<SettingsUiState> = combine(
        combine(settingsRepository.settings, secretStore.hasApiKey, secretStore.hasCloudTtsApiKey, ::Triple),
        voices,
        modelManager.states,
        usageRepository.usage,
        usageRepository.today
    ) { (settings, hasKey, hasCloudKey), voices, models, usage, today ->
        SettingsUiState(
            settings = settings,
            hasApiKey = hasKey,
            hasCloudTtsApiKey = hasCloudKey,
            voices = voices,
            searchModel = models[searchModelId] ?: ModelState.NotInstalled,
            usage = usage,
            today = today
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(mode: ThemeMode) = launch { settingsRepository.setThemeMode(mode) }

    fun setTtsEngine(engine: TtsEngineKind) = launch {
        settingsRepository.setTtsEngine(engine)
        refreshVoices()
    }

    fun setCloudVoiceTier(tier: CloudVoiceTier) = launch { settingsRepository.setCloudVoiceTier(tier) }

    fun saveCloudTtsApiKey(key: String) = launch {
        if (key.isNotBlank()) secretStore.setCloudTtsApiKey(key)
        refreshVoices()
    }

    fun removeCloudTtsApiKey() = launch {
        secretStore.clearCloudTtsApiKey()
        refreshVoices()
    }

    fun setSpeed(speed: Float) = launch { settingsRepository.setReadingSpeed(speed) }

    fun setChatModel(model: String) = launch { settingsRepository.setChatModel(model) }

    fun setSummaryModel(model: String) = launch { settingsRepository.setSummaryModel(model) }

    fun setAutoCharacterAnalysis(enabled: Boolean) = launch { settingsRepository.setAutoCharacterAnalysis(enabled) }

    fun saveApiKey(key: String) = launch { if (key.isNotBlank()) secretStore.setApiKey(key) }

    fun removeApiKey() = launch { secretStore.clearApiKey() }

    fun setDailyBudget(tokens: Long) = launch { usageRepository.setDailyBudget(tokens) }

    fun resetUsage() = launch { usageRepository.reset() }

    /** Vuelve a mirar qué voces tiene el sistema (al entrar y al volver de instalar una). */
    fun refreshVoices() = launch {
        voices.value = Language.entries.associateWith { playback.voiceAvailability(it) }
    }

    fun downloadSearchModel() = modelManager.download(searchModelId)

    fun cancelSearchModelDownload() = modelManager.cancel(searchModelId)

    fun deleteSearchModel() = launch { modelManager.delete(searchModelId) }

    private val _ragEval = MutableStateFlow<RagEvalState?>(null)
    val ragEval: StateFlow<RagEvalState?> = _ragEval

    /** Ejecuta el fichero de evaluación [uri] contra los libros ya indexados. */
    fun runRagEvaluation(uri: Uri) = launch {
        if (state.value.searchModel !is ModelState.Installed) {
            _ragEval.value = RagEvalState.Failed("Descarga antes el modelo para el chat.")
            return@launch
        }
        _ragEval.value = RagEvalState.Running
        _ragEval.value = try {
            val text = withContext(io) {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } ?: error("No se pudo leer el fichero.")
            RagEvalState.Done(ragEvaluator.run(EvalSet.parse(text)))
        } catch (e: SerializationException) {
            RagEvalState.Failed("El fichero no tiene el formato esperado: ${e.message}")
        } catch (e: IllegalStateException) {
            RagEvalState.Failed(e.message.orEmpty())
        }
    }

    fun dismissRagEvaluation() {
        _ragEval.value = null
    }

    fun previewVoice(language: Language) = launch {
        playback.preview(if (language == Language.ENGLISH) PREVIEW_TEXT_EN else PREVIEW_TEXT, language)
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val PREVIEW_TEXT = "Hola. Así sonará la lectura en voz alta de tus libros."
        const val PREVIEW_TEXT_EN = "Hello. This is how your English books will sound when read aloud."
    }
}
