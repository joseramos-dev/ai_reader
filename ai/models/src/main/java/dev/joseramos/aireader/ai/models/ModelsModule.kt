package dev.joseramos.aireader.ai.models

import dev.joseramos.aireader.core.common.ApplicationScope
import dev.joseramos.aireader.core.common.IoDispatcher
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.koin.dsl.module

private const val CONNECT_TIMEOUT_S = 30L
private const val READ_TIMEOUT_S = 60L

val modelsModule = module {
    single {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }
    factory { ModelDownloader(get()) }
    single { ModelManager(get(), get(), get(), get(ApplicationScope), get(IoDispatcher)) }
    factory { BundledModelsInstaller(get(), get(ApplicationScope)) }
}
