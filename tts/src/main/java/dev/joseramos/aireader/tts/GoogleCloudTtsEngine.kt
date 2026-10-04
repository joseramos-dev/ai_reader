package dev.joseramos.aireader.tts

import android.util.Base64
import dev.joseramos.aireader.core.common.IoDispatcher
import dev.joseramos.aireader.core.data.settings.SecretStore
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import dev.joseramos.aireader.text.Language
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Voz de Google Cloud Text-to-Speech (REST), alternativa a la del sistema: hace falta una clave
 * de API propia, de un proyecto de Google Cloud con esa API habilitada (distinta de la clave de
 * Gemini). Se pide siempre `audioEncoding: LINEAR16`, que Google devuelve envuelto en un WAV, así
 * que se decodifica con el mismo [WavReader] que usa la voz del sistema. La voz exacta por
 * idioma y nivel de calidad está en [CloudVoices]; no hay selección manual de voces.
 */
@Singleton
class GoogleCloudTtsEngine @Inject constructor(
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher
) : TtsEngine {
    private val http = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    @Volatile private var voice: CloudTtsVoice? = null

    override suspend fun load(language: Language): VoiceAvailability {
        if (secrets.cloudTtsApiKey() == null) return VoiceAvailability.NEEDS_API_KEY
        val tier = settings.settings.first().cloudVoiceTier
        voice = CloudTtsVoice(language.locale.toLanguageTag(), CloudVoices.nameFor(language, tier))
        return VoiceAvailability.READY
    }

    override suspend fun availability(language: Language): VoiceAvailability =
        if (secrets.cloudTtsApiKey() != null) VoiceAvailability.READY else VoiceAvailability.NEEDS_API_KEY

    override suspend fun synthesize(text: String, speed: Float): Pcm = withContext(io) {
        val key = secrets.cloudTtsApiKey() ?: throw IOException("Falta la clave de API de Google Cloud TTS")
        val target = checkNotNull(voice) { "La voz no está cargada" }
        val call = http.newCall(httpRequest(text, speed, target, key))
        val body = try {
            call.execute().use { response ->
                ensureSuccess(response)
                response.body.string()
            }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (e: IOException) {
            throw translateCloudTtsError(e)
        }
        val parsed = try {
            cloudTtsJson.decodeFromString<CloudTtsResponse>(body)
        } catch (e: SerializationException) {
            throw IOException("Respuesta inesperada de Google Cloud TTS", e)
        }
        WavReader.read(Base64.decode(parsed.audioContent, Base64.DEFAULT))
    }

    override fun release() {
        voice = null
    }

    private fun httpRequest(text: String, speed: Float, voice: CloudTtsVoice, key: String): Request {
        val payload = CloudTtsRequest(
            input = CloudTtsInput(text),
            voice = voice,
            audioConfig = CloudTtsAudioConfig(audioEncoding = "LINEAR16", speakingRate = speed)
        )
        return Request.Builder()
            .url(SYNTHESIZE_URL)
            .header("x-goog-api-key", key)
            .post(cloudTtsJson.encodeToString(CloudTtsRequest.serializer(), payload).toRequestBody(JSON))
            .build()
    }

    private fun ensureSuccess(response: Response) {
        if (response.isSuccessful) return
        val body = response.body.string()
        val error = runCatching { cloudTtsJson.decodeFromString<CloudTtsErrorBody>(body).error }.getOrNull()
        throw CloudTtsApiException(response.code, error?.message ?: body)
    }

    private companion object {
        const val SYNTHESIZE_URL = "https://texttospeech.googleapis.com/v1/text:synthesize"
        const val CONNECT_TIMEOUT_S = 30L
        const val READ_TIMEOUT_S = 60L
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
