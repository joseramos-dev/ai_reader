package dev.joseramos.aireader.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.io.encoding.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Secretos de la app: la clave de API de Gemini. Se guarda cifrada con el [SecretCipher] de la plataforma
 * (la clave de cifrado nunca sale del sistema). El valor no se registra nunca en logs.
 */
class SecretStore(private val dataStore: DataStore<Preferences>, private val cipher: SecretCipher) {
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
        Base64.encode(cipher.encrypt(plain.toByteArray(), GEMINI_ASSOCIATED_DATA))

    private fun decrypt(stored: String): String = String(cipher.decrypt(Base64.decode(stored), GEMINI_ASSOCIATED_DATA))

    companion object {
        const val SECRETS_STORE = "secrets"
        private val API_KEY = stringPreferencesKey("gemini_api_key")

        /** Clave de Claude de versiones anteriores: ya no se usa y se borra al guardar o quitar la nueva. */
        private val LEGACY_ANTHROPIC_KEY = stringPreferencesKey("anthropic_api_key")

        /** Liga el texto cifrado a este uso: no se puede trasplantar a otra preferencia. */
        private val GEMINI_ASSOCIATED_DATA = "aireader:gemini_api_key".toByteArray()
    }
}
