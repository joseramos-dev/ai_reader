package dev.joseramos.aireader.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Tokens consumidos en la API de Claude desde la instalación (o desde que se reinició el contador). */
data class ApiUsage(val inputTokens: Long = 0, val outputTokens: Long = 0, val cachedInputTokens: Long = 0)

@Singleton
class UsageRepository @Inject constructor(
    @Named(SettingsRepository.SETTINGS_STORE) private val dataStore: DataStore<Preferences>
) {
    val usage: Flow<ApiUsage> = dataStore.data.map {
        ApiUsage(it[INPUT] ?: 0, it[OUTPUT] ?: 0, it[CACHED] ?: 0)
    }

    suspend fun add(input: Long, output: Long, cachedInput: Long) {
        dataStore.edit {
            it[INPUT] = (it[INPUT] ?: 0) + input
            it[OUTPUT] = (it[OUTPUT] ?: 0) + output
            it[CACHED] = (it[CACHED] ?: 0) + cachedInput
        }
    }

    suspend fun reset() {
        dataStore.edit {
            it.remove(INPUT)
            it.remove(OUTPUT)
            it.remove(CACHED)
        }
    }

    private companion object {
        val INPUT = longPreferencesKey("usage_input_tokens")
        val OUTPUT = longPreferencesKey("usage_output_tokens")
        val CACHED = longPreferencesKey("usage_cached_input_tokens")
    }
}
