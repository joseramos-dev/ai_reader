package dev.joseramos.aireader.tts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Cuerpos JSON de `https://texttospeech.googleapis.com/v1/text:synthesize`, solo con los campos
// que usa la app: https://cloud.google.com/text-to-speech/docs/reference/rest

@Serializable
internal data class CloudTtsInput(val text: String)

@Serializable
internal data class CloudTtsVoice(val languageCode: String, val name: String)

@Serializable
internal data class CloudTtsAudioConfig(val audioEncoding: String, val speakingRate: Float)

@Serializable
internal data class CloudTtsRequest(
    val input: CloudTtsInput,
    val voice: CloudTtsVoice,
    val audioConfig: CloudTtsAudioConfig
)

/** [audioContent]: el audio en base64, en el formato pedido en [CloudTtsAudioConfig.audioEncoding]. */
@Serializable
internal data class CloudTtsResponse(val audioContent: String = "")

@Serializable
internal data class CloudTtsErrorBody(val error: CloudTtsError? = null)

@Serializable
internal data class CloudTtsError(val code: Int = 0, val message: String = "", val status: String = "")

internal val cloudTtsJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
