package dev.joseramos.aireader.core.data.settings

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.crypto.tink.Aead
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Secretos de la app (por ahora, la clave de API de Gemini). Se guardan cifrados con
 * AES-256-GCM (Tink); la clave de cifrado vive en el Android Keystore y nunca sale de él.
 * Los valores no se registran nunca en logs.
 */
@Singleton
class SecretStore @Inject constructor(
    @Named(SECRETS_STORE) private val dataStore: DataStore<Preferences>,
    private val aead: Aead
) {
    val hasApiKey: Flow<Boolean> = dataStore.data.map { it[API_KEY] != null }

    suspend fun apiKey(): String? = dataStore.data.first()[API_KEY]?.let(::decrypt)

    suspend fun setApiKey(value: String) {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "La clave no puede estar vacía" }
        dataStore.edit {
            it[API_KEY] = encrypt(trimmed)
            it.remove(LEGACY_ANTHROPIC_KEY)
        }
    }

    suspend fun clearApiKey() {
        dataStore.edit {
            it.remove(API_KEY)
            it.remove(LEGACY_ANTHROPIC_KEY)
        }
    }

    private fun encrypt(plain: String): String =
        Base64.encodeToString(aead.encrypt(plain.toByteArray(), ASSOCIATED_DATA), Base64.NO_WRAP)

    private fun decrypt(stored: String): String =
        String(aead.decrypt(Base64.decode(stored, Base64.NO_WRAP), ASSOCIATED_DATA))

    companion object {
        const val SECRETS_STORE = "secrets"
        private val API_KEY = stringPreferencesKey("gemini_api_key")

        /** Clave de Claude de versiones anteriores: ya no se usa y se borra al guardar o quitar la nueva. */
        private val LEGACY_ANTHROPIC_KEY = stringPreferencesKey("anthropic_api_key")

        /** Liga el texto cifrado a este uso: no se puede trasplantar a otra preferencia. */
        private val ASSOCIATED_DATA = "aireader:gemini_api_key".toByteArray()
    }
}
