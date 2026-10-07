package dev.joseramos.aireader.ai.models

import android.content.Context
import org.koin.core.module.Module
import org.koin.dsl.module

/** Los modelos empaquetados van en los assets del APK. */
actual val modelsPlatformModule: Module = module {
    single<BundledFiles> {
        val assets = get<Context>().assets
        BundledFiles { path -> runCatching { assets.open(path) }.getOrNull() }
    }
}
