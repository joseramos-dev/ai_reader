package dev.joseramos.aireader.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Dispatcher para disco y red. Se inyecta para poder sustituirlo en las pruebas. */
val IoDispatcher = named("io")

/** Dispatcher para cálculo intensivo (inferencia, troceado de texto…). */
val DefaultDispatcher = named("default")

/** Ámbito que vive lo mismo que la app, para trabajo que no debe cancelarse al salir de una pantalla. */
val ApplicationScope = named("applicationScope")

/** Lo que aporta cada plataforma: [KeyValueStoreFactory] e [InstallStamp]. Necesita un [AppDirs] registrado. */
expect val commonPlatformModule: Module

val commonModule = module {
    includes(commonPlatformModule)

    single<CoroutineDispatcher>(IoDispatcher) { Dispatchers.IO }
    single<CoroutineDispatcher>(DefaultDispatcher) { Dispatchers.Default }
    single<CoroutineScope>(ApplicationScope) {
        CoroutineScope(SupervisorJob() + get<CoroutineDispatcher>(DefaultDispatcher))
    }
}
