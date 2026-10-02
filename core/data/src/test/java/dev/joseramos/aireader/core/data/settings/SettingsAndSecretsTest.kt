package dev.joseramos.aireader.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsAndSecretsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.store(name: String) =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "$name.preferences_pb") }

    private fun aead(): Aead {
        AeadConfig.register()
        return KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    @Test
    fun settingsHaveSensibleDefaultsAndPersist() = runTest {
        val repository = SettingsRepository(store("settings"))
        assertEquals(AppSettings(), repository.settings.first())
        assertEquals("gemini-3.8-flash", repository.settings.first().chatModel)
        assertEquals("gemini-3.1-flash-lite", repository.settings.first().summaryModel)

        repository.setThemeMode(ThemeMode.DARK)
        repository.setReadingSpeed(5f)

        val settings = repository.settings.first()
        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertEquals(AppSettings.MAX_SPEED, settings.readingSpeed)
    }

    @Test
    fun apiKeyIsStoredEncryptedAndCanBeCleared() = runTest {
        val dataStore = store("secrets")
        val secrets = SecretStore(dataStore, aead())
        assertFalse(secrets.hasApiKey.first())

        secrets.setApiKey("  AIzaPrueba-123  ")

        assertTrue(secrets.hasApiKey.first())
        assertEquals("AIzaPrueba-123", secrets.apiKey())
        val raw = dataStore.data.first().asMap().values.single() as String
        assertFalse("La clave no debe guardarse en claro", raw.contains("AIzaPrueba"))

        secrets.clearApiKey()
        assertNull(secrets.apiKey())
    }

    @Test
    fun dailyUsageCountsTodayAndResetsAtPacificMidnight() = runTest {
        val dataStore = store("settings")
        // 10:00 en Madrid = 01:00 en Los Ángeles: el «día de cuota» aún es el 2.
        val morning = Clock.fixed(Instant.parse("2026-10-02T08:00:00Z"), ZoneOffset.UTC)
        val usage = UsageRepository(dataStore, morning)
        usage.setDailyBudget(1_000)
        usage.add(input = 200, output = 50, cachedInput = 0)

        val today = usage.today.first()
        assertEquals(250L, today.tokens)
        assertEquals(0.75f, today.remaining, 0.001f)
        assertEquals(Instant.parse("2026-10-03T07:00:00Z").toEpochMilli(), today.resetsAt)

        usage.markDailyQuotaExhausted()
        assertEquals(0f, usage.today.first().remaining)

        // Al día siguiente (hora del Pacífico) el contador empieza de cero.
        val tomorrow = UsageRepository(dataStore, Clock.fixed(Instant.parse("2026-10-03T08:00:00Z"), ZoneOffset.UTC))
        assertEquals(0L, tomorrow.today.first().tokens)
        assertEquals(1f, tomorrow.today.first().remaining)
    }

    @Test
    fun claudeModelsSavedByOlderVersionsFallBackToGemini() = runTest {
        val dataStore = store("settings")
        val repository = SettingsRepository(dataStore)
        repository.setChatModel("claude-sonnet-5-5")
        repository.setSummaryModel("gemini-3.8-flash")

        val settings = repository.settings.first()
        assertEquals(AppSettings.DEFAULT_CHAT_MODEL, settings.chatModel)
        assertEquals("gemini-3.8-flash", settings.summaryModel)
    }
}
