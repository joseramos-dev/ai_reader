package dev.joseramos.aireader.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Tokens consumidos en la API de Gemini desde la instalación (o desde que se reinició el contador). */
data class ApiUsage(val inputTokens: Long = 0, val outputTokens: Long = 0, val cachedInputTokens: Long = 0)

/**
 * Consumo de IA del día frente al límite diario elegido en Ajustes. La API de Gemini no dice cuánta
 * cuota gratuita queda, así que el indicador se basa en lo gastado hoy; si Gemini responde que la
 * cuota del día se ha agotado, [exhausted] lo marca como vacío aunque no se haya llegado al límite.
 */
data class DailyUsage(
    val tokens: Long = 0,
    val requests: Long = 0,
    val budget: Long = DEFAULT_DAILY_BUDGET,
    val exhausted: Boolean = false,
    /** Momento (epoch ms) en que se renueva la cuota: medianoche en la hora del Pacífico. */
    val resetsAt: Long = 0
) {
    /** Lo que queda del día, de 1 (sin usar) a 0 (agotado). */
    val remaining: Float
        get() = if (exhausted) 0f else (1f - tokens.toFloat() / budget.coerceAtLeast(1)).coerceIn(0f, 1f)

    companion object {
        const val DEFAULT_DAILY_BUDGET = 1_000_000L
        val BUDGET_OPTIONS = listOf(250_000L, 500_000L, 1_000_000L, 2_000_000L, 5_000_000L)
    }
}

@Singleton
class UsageRepository internal constructor(private val dataStore: DataStore<Preferences>, private val clock: Clock) {
    @Inject
    constructor(@Named(SettingsRepository.SETTINGS_STORE) dataStore: DataStore<Preferences>) :
        this(dataStore, Clock.systemUTC())

    val usage: Flow<ApiUsage> = dataStore.data.map {
        ApiUsage(it[INPUT] ?: 0, it[OUTPUT] ?: 0, it[CACHED] ?: 0)
    }

    /** Consumo de hoy (el día de las cuotas de Gemini, que cambia a medianoche del Pacífico). */
    val today: Flow<DailyUsage> = dataStore.data.map { prefs ->
        val day = quotaDay()
        val sameDay = prefs[DAY] == day.toString()
        DailyUsage(
            tokens = if (sameDay) prefs[DAY_TOKENS] ?: 0 else 0,
            requests = if (sameDay) prefs[DAY_REQUESTS] ?: 0 else 0,
            budget = prefs[DAILY_BUDGET] ?: DailyUsage.DEFAULT_DAILY_BUDGET,
            exhausted = prefs[EXHAUSTED_DAY] == day.toString(),
            resetsAt = day.plusDays(1).atStartOfDay(QUOTA_ZONE).toInstant().toEpochMilli()
        )
    }

    /** Suma una petición correcta: al total y al día (y, si había cuota agotada, ya no lo está). */
    suspend fun add(input: Long, output: Long, cachedInput: Long) {
        val day = quotaDay().toString()
        dataStore.edit {
            it[INPUT] = (it[INPUT] ?: 0) + input
            it[OUTPUT] = (it[OUTPUT] ?: 0) + output
            it[CACHED] = (it[CACHED] ?: 0) + cachedInput
            if (it[DAY] != day) {
                it[DAY] = day
                it[DAY_TOKENS] = 0
                it[DAY_REQUESTS] = 0
            }
            it[DAY_TOKENS] = (it[DAY_TOKENS] ?: 0) + input + output
            it[DAY_REQUESTS] = (it[DAY_REQUESTS] ?: 0) + 1
            it.remove(EXHAUSTED_DAY)
        }
    }

    /** Gemini ha respondido que la cuota diaria se ha agotado. */
    suspend fun markDailyQuotaExhausted() {
        dataStore.edit { it[EXHAUSTED_DAY] = quotaDay().toString() }
    }

    suspend fun setDailyBudget(tokens: Long) {
        dataStore.edit { it[DAILY_BUDGET] = tokens.coerceAtLeast(1) }
    }

    suspend fun reset() {
        dataStore.edit {
            it.remove(INPUT)
            it.remove(OUTPUT)
            it.remove(CACHED)
            it.remove(DAY_TOKENS)
            it.remove(DAY_REQUESTS)
            it.remove(EXHAUSTED_DAY)
        }
    }

    private fun quotaDay(): LocalDate = ZonedDateTime.now(clock.withZone(QUOTA_ZONE)).toLocalDate()

    private companion object {
        /** Las cuotas diarias de la API de Gemini se renuevan a medianoche en la hora del Pacífico. */
        val QUOTA_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
        val INPUT = longPreferencesKey("usage_input_tokens")
        val OUTPUT = longPreferencesKey("usage_output_tokens")
        val CACHED = longPreferencesKey("usage_cached_input_tokens")
        val DAY = stringPreferencesKey("usage_day")
        val DAY_TOKENS = longPreferencesKey("usage_day_tokens")
        val DAY_REQUESTS = longPreferencesKey("usage_day_requests")
        val EXHAUSTED_DAY = stringPreferencesKey("usage_exhausted_day")
        val DAILY_BUDGET = longPreferencesKey("usage_daily_budget")
    }
}
