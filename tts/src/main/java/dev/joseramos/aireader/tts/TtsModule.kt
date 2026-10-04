package dev.joseramos.aireader.tts

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TtsModule {
    @Binds
    abstract fun bindTtsEngine(engine: SystemTtsEngine): TtsEngine
}
