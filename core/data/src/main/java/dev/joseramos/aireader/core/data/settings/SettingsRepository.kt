package dev.joseramos.aireader.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val readingSpeed: Float = 1.0f,
    val chatModel: String = DEFAULT_CHAT_MODEL,
    val summaryModel: String = DEFAULT_SUMMARY_MODEL,
    /** Escala de letra del modo lectura de texto. */
    val textScale: Float = 1.0f,
    /** Analizar los personajes de las novelas al indexarlas; si no, solo cuando se pide. */
    val autoCharacterAnalysis: Boolean = true
) {
    companion object {
        // Gemini en lugar de Claude (docs/02-diseno-tecnico.md §10): Flash para el chat y Flash-Lite,
        // con más margen en el nivel gratuito, para resúmenes, personajes y tareas por volumen.
        const val DEFAULT_CHAT_MODEL = "gemini-3.8-flash"
        const val DEFAULT_SUMMARY_MODEL = "gemini-3.1-flash-lite"
        private const val MODEL_PREFIX = "gemini-"

        /** Los modelos de Claude que pudiera haber guardados de versiones anteriores ya no sirven. */
        fun validModel(id: String?, default: String) = id?.takeIf { it.startsWith(MODEL_PREFIX) } ?: default
        const val MIN_SPEED = 0.75f
        const val MAX_SPEED = 2.0f
        const val MIN_TEXT_SCALE = 0.8f
        const val MAX_TEXT_SCALE = 1.6f
    }
}

@Singleton
class SettingsRepository @Inject constructor(@Named(SETTINGS_STORE) private val dataStore: DataStore<Preferences>) {
    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            readingSpeed = prefs[SPEED] ?: 1.0f,
            chatModel = AppSettings.validModel(prefs[CHAT_MODEL], AppSettings.DEFAULT_CHAT_MODEL),
            summaryModel = AppSettings.validModel(prefs[SUMMARY_MODEL], AppSettings.DEFAULT_SUMMARY_MODEL),
            textScale = prefs[TEXT_SCALE] ?: 1.0f,
            autoCharacterAnalysis = prefs[AUTO_CHARACTERS] ?: true
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME] = mode.name }
    }

    suspend fun setReadingSpeed(speed: Float) {
        dataStore.edit { it[SPEED] = speed.coerceIn(AppSettings.MIN_SPEED, AppSettings.MAX_SPEED) }
    }

    suspend fun setChatModel(model: String) {
        dataStore.edit { it[CHAT_MODEL] = model }
    }

    suspend fun setSummaryModel(model: String) {
        dataStore.edit { it[SUMMARY_MODEL] = model }
    }

    suspend fun setTextScale(scale: Float) {
        dataStore.edit { it[TEXT_SCALE] = scale.coerceIn(AppSettings.MIN_TEXT_SCALE, AppSettings.MAX_TEXT_SCALE) }
    }

    suspend fun setAutoCharacterAnalysis(enabled: Boolean) {
        dataStore.edit { it[AUTO_CHARACTERS] = enabled }
    }

    companion object {
        const val SETTINGS_STORE = "settings"
        private val THEME = stringPreferencesKey("theme_mode")
        private val SPEED = floatPreferencesKey("reading_speed")
        private val CHAT_MODEL = stringPreferencesKey("chat_model")
        private val SUMMARY_MODEL = stringPreferencesKey("summary_model")
        private val TEXT_SCALE = floatPreferencesKey("text_scale")
        private val AUTO_CHARACTERS = booleanPreferencesKey("auto_character_analysis")
    }
}
