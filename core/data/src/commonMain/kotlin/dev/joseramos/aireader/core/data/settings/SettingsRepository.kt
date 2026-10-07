package dev.joseramos.aireader.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val readingSpeed: Float = 1.0f,
    val chatModel: String = DEFAULT_CHAT_MODEL,
    val analysisModel: String = DEFAULT_ANALYSIS_MODEL,
    /** Escala de letra del modo lectura de texto. */
    val textScale: Float = 1.0f,
    /** Analizar los personajes de las novelas al indexarlas; si no, solo cuando se pide. */
    val autoCharacterAnalysis: Boolean = true
) {
    companion object {
        // Gemini en lugar de Claude (docs/02-diseno-tecnico.md §10): Flash para el chat y Flash-Lite,
        // con más margen en el nivel gratuito, para hechos clave, personajes y tareas por volumen.
        const val DEFAULT_CHAT_MODEL = "gemini-3.8-flash"
        const val DEFAULT_ANALYSIS_MODEL = "gemini-3.1-flash-lite"
        private const val MODEL_PREFIX = "gemini-"

        /** Los modelos de Claude que pudiera haber guardados de versiones anteriores ya no sirven. */
        fun validModel(id: String?, default: String) = id?.takeIf { it.startsWith(MODEL_PREFIX) } ?: default
        const val MIN_SPEED = 0.75f
        const val MAX_SPEED = 2.0f
        const val MIN_TEXT_SCALE = 0.8f
        const val MAX_TEXT_SCALE = 1.6f
    }
}

class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            readingSpeed = prefs[SPEED] ?: 1.0f,
            chatModel = AppSettings.validModel(prefs[CHAT_MODEL], AppSettings.DEFAULT_CHAT_MODEL),
            analysisModel = AppSettings.validModel(prefs[ANALYSIS_MODEL], AppSettings.DEFAULT_ANALYSIS_MODEL),
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

    suspend fun setAnalysisModel(model: String) {
        dataStore.edit { it[ANALYSIS_MODEL] = model }
    }

    suspend fun setTextScale(scale: Float) {
        dataStore.edit { it[TEXT_SCALE] = scale.coerceIn(AppSettings.MIN_TEXT_SCALE, AppSettings.MAX_TEXT_SCALE) }
    }

    suspend fun setAutoCharacterAnalysis(enabled: Boolean) {
        dataStore.edit { it[AUTO_CHARACTERS] = enabled }
    }

    /**
     * «Anti-spoilers» del chat de un libro: activado salvo que se haya desactivado para ese libro.
     * Se guardan solo los libros en los que está desactivado.
     */
    fun observeAntiSpoilers(bookId: String): Flow<Boolean> =
        dataStore.data.map { bookId !in it[SPOILERS_ALLOWED].orEmpty() }.distinctUntilChanged()

    suspend fun setAntiSpoilers(bookId: String, enabled: Boolean) {
        dataStore.edit { prefs ->
            val allowed = prefs[SPOILERS_ALLOWED].orEmpty()
            prefs[SPOILERS_ALLOWED] = if (enabled) allowed - bookId else allowed + bookId
        }
    }

    companion object {
        const val SETTINGS_STORE = "settings"
        private val THEME = stringPreferencesKey("theme_mode")
        private val SPEED = floatPreferencesKey("reading_speed")
        private val CHAT_MODEL = stringPreferencesKey("chat_model")

        // Se llamaba «modelo de resúmenes»: la clave se conserva para no perder lo que se eligió.
        private val ANALYSIS_MODEL = stringPreferencesKey("summary_model")
        private val TEXT_SCALE = floatPreferencesKey("text_scale")
        private val AUTO_CHARACTERS = booleanPreferencesKey("auto_character_analysis")
        private val SPOILERS_ALLOWED = stringSetPreferencesKey("spoilers_allowed_books")
    }
}
