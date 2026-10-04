package dev.joseramos.aireader.tts

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TtsModule {
    /** Motor de voz de la app: elige entre el del sistema y Google Cloud TTS según Ajustes. */
    @Binds
    abstract fun bindTtsEngine(engine: SelectedTtsEngine): TtsEngine
}
