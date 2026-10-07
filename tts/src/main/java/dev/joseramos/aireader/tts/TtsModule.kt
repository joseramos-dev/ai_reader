package dev.joseramos.aireader.tts

import dev.joseramos.aireader.core.common.ApplicationScope
import dev.joseramos.aireader.core.common.IoDispatcher
import org.koin.dsl.bind
import org.koin.dsl.module

val ttsModule = module {
    single { SystemTtsEngine(get(), get(IoDispatcher)) } bind TtsEngine::class
    single { PlaybackEngine(get(), get(), get(), get(), get(), get(ApplicationScope)) }
    single { PlaybackController(get(), get(), get(), get()) }
}
