package dev.joseramos.aireader.tts

import dev.joseramos.aireader.core.data.settings.SettingsRepository
import dev.joseramos.aireader.core.data.settings.TtsEngineKind
import dev.joseramos.aireader.text.Language
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * El motor de voz que usa la app en cada momento, según lo elegido en Ajustes. Permite cambiar
 * de motor (sistema o Google Cloud TTS) sin reiniciar la app ni la reproducción en curso: el
 * motor se fija en [load], al empezar una sesión de lectura, y se mantiene para las frases de
 * esa sesión, igual que cada motor ya mantiene su propio idioma cargado.
 */
@Singleton
class SelectedTtsEngine @Inject constructor(
    private val settings: SettingsRepository,
    private val system: SystemTtsEngine,
    private val cloud: GoogleCloudTtsEngine
) : TtsEngine {
    @Volatile private var current: TtsEngine = system

    override suspend fun load(language: Language): VoiceAvailability {
        current = engineFor(settings.settings.first().ttsEngine)
        return current.load(language)
    }

    override suspend fun availability(language: Language): VoiceAvailability =
        engineFor(settings.settings.first().ttsEngine).availability(language)

    override suspend fun synthesize(text: String, speed: Float): Pcm = current.synthesize(text, speed)

    override fun release() = current.release()

    private fun engineFor(kind: TtsEngineKind): TtsEngine = when (kind) {
        TtsEngineKind.SYSTEM -> system
        TtsEngineKind.GOOGLE_CLOUD -> cloud
    }
}
