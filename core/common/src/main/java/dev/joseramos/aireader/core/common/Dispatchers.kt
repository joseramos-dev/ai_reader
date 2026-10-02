package dev.joseramos.aireader.core.common

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Dispatcher para disco y red. Se inyecta para poder sustituirlo en las pruebas. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Dispatcher para cálculo intensivo (inferencia, troceado de texto…). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** Ámbito que vive lo mismo que la app, para trabajo que no debe cancelarse al salir de una pantalla. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @ApplicationScope
    @javax.inject.Singleton
    fun provideApplicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)
}
