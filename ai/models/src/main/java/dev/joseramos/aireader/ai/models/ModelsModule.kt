package dev.joseramos.aireader.ai.models

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object ModelsModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private const val CONNECT_TIMEOUT_S = 30L
    private const val READ_TIMEOUT_S = 60L
}
