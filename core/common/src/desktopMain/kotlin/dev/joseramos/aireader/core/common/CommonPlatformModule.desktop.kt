package dev.joseramos.aireader.core.common

import java.io.File
import java.util.Properties
import org.koin.core.module.Module
import org.koin.dsl.module

/** Guarda los valores en un fichero `.properties` (se reescribe entero en cada cambio: es estado pequeño). */
private class PropertiesStore(private val file: File) : KeyValueStore {
    private val properties = Properties().apply {
        if (file.isFile) runCatching { file.inputStream().use(::load) }
    }

    @Synchronized
    override fun getInt(key: String, default: Int) = properties.getProperty(key)?.toIntOrNull() ?: default

    @Synchronized
    override fun getBoolean(key: String, default: Boolean) =
        properties.getProperty(key)?.toBooleanStrictOrNull() ?: default

    @Synchronized
    override fun getString(key: String): String? = properties.getProperty(key)

    @Synchronized
    override fun keys(): Set<String> = properties.stringPropertyNames().toSet()

    @Synchronized
    override fun edit(commit: Boolean, block: KeyValueStore.Editor.() -> Unit) {
        object : KeyValueStore.Editor {
            override fun putInt(key: String, value: Int) {
                properties.setProperty(key, value.toString())
            }

            override fun putBoolean(key: String, value: Boolean) {
                properties.setProperty(key, value.toString())
            }

            override fun putString(key: String, value: String) {
                properties.setProperty(key, value)
            }

            override fun remove(key: String) {
                properties.remove(key)
            }

            override fun clear() {
                properties.clear()
            }
        }.block()
        file.parentFile?.mkdirs()
        file.outputStream().use { properties.store(it, null) }
    }
}

/**
 * Identifica la instalación: la versión que fija jpackage al empaquetar y, si no la hay (al ejecutar desde
 * Gradle), la fecha del código de la app.
 */
private fun installStamp(): String? {
    val version = System.getProperty("jpackage.app-version")
    val code = runCatching {
        File(PropertiesStore::class.java.protectionDomain.codeSource.location.toURI()).lastModified()
    }.getOrNull()
    return listOfNotNull(version, code?.toString()).joinToString(":").ifEmpty { null }
}

actual val commonPlatformModule: Module = module {
    single<KeyValueStoreFactory> {
        val dirs = get<AppDirs>()
        KeyValueStoreFactory { name -> PropertiesStore(File(dirs.files, "prefs/$name.properties")) }
    }
    single<InstallStamp> { InstallStamp { installStamp() } }
}
