package dev.joseramos.aireader.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import java.io.File
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
        assertEquals("claude-sonnet-5-5", repository.settings.first().chatModel)
        assertEquals("claude-haiku-4-5", repository.settings.first().summaryModel)

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

        secrets.setApiKey("  sk-ant-prueba-123  ")

        assertTrue(secrets.hasApiKey.first())
        assertEquals("sk-ant-prueba-123", secrets.apiKey())
        val raw = dataStore.data.first().asMap().values.single() as String
        assertFalse("La clave no debe guardarse en claro", raw.contains("sk-ant"))

        secrets.clearApiKey()
        assertNull(secrets.apiKey())
    }
}
