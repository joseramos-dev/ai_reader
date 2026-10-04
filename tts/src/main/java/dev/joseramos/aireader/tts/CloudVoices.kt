package dev.joseramos.aireader.tts

import dev.joseramos.aireader.core.data.settings.CloudVoiceTier
import dev.joseramos.aireader.text.Language

/**
 * Voz exacta de Google Cloud TTS para cada idioma y nivel de calidad (no se consulta el catálogo
 * de voces en tiempo real: la selección es automática, como la del motor del sistema). Si Google
 * cambia o retira alguno de estos nombres, hay que actualizar esta tabla — ver la lista de voces
 * disponibles en cloud.google.com/text-to-speech/docs/voices.
 */
internal object CloudVoices {
    fun nameFor(language: Language, tier: CloudVoiceTier): String = when (language) {
        Language.SPANISH -> when (tier) {
            CloudVoiceTier.STANDARD -> "es-ES-Standard-A"
            CloudVoiceTier.WAVENET -> "es-ES-Wavenet-C"
            CloudVoiceTier.NEURAL2 -> "es-ES-Neural2-A"
        }
        Language.ENGLISH -> when (tier) {
            CloudVoiceTier.STANDARD -> "en-US-Standard-C"
            CloudVoiceTier.WAVENET -> "en-US-Wavenet-C"
            CloudVoiceTier.NEURAL2 -> "en-US-Neural2-C"
        }
    }
}
