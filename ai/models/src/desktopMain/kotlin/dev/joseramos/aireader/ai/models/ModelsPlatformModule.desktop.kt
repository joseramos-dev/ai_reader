package dev.joseramos.aireader.ai.models

import java.io.File
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Los modelos empaquetados van en la carpeta de recursos del instalador (`compose.application.resources.dir`,
 * que fija Compose Desktop al empaquetar). Al ejecutar desde Gradle no hay recursos y el modelo se descarga.
 */
actual val modelsPlatformModule: Module = module {
    single<BundledFiles> {
        val root = System.getProperty("compose.application.resources.dir")?.let(::File)
        BundledFiles { path -> root?.let { File(it, path) }?.takeIf { it.isFile }?.inputStream() }
    }
}
