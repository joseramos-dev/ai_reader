package dev.joseramos.aireader.tts

import dev.joseramos.aireader.core.common.ApplicationScope
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Lo que aporta cada plataforma: el [TtsEngine], la [AudioSink] ([AudioSinkFactory]), el foco de audio, el
 * [WakeLock], el [PlaybackBridge] y los [VoiceSettings].
 */
expect val ttsPlatformModule: Module

val ttsModule = module {
    includes(ttsPlatformModule)

    single { PlaybackEngine(get(), get(), get(), get(), get(ApplicationScope), get(), get(), get()) }
    single { PlaybackController(get(), get(), get(), get()) }
}
