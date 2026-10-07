package dev.joseramos.aireader.core.common

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import org.koin.core.module.Module
import org.koin.dsl.module

private class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)

    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun keys(): Set<String> = prefs.all.keys.toSet()

    override fun edit(commit: Boolean, block: KeyValueStore.Editor.() -> Unit) {
        prefs.edit(commit = commit) {
            val editor = this
            object : KeyValueStore.Editor {
                override fun putInt(key: String, value: Int) {
                    editor.putInt(key, value)
                }

                override fun putBoolean(key: String, value: Boolean) {
                    editor.putBoolean(key, value)
                }

                override fun putString(key: String, value: String) {
                    editor.putString(key, value)
                }

                override fun remove(key: String) {
                    editor.remove(key)
                }

                override fun clear() {
                    editor.clear()
                }
            }.block()
        }
    }
}

/** Cambia con cada instalación o actualización, aunque no cambie el número de versión (depuración). */
private fun installStamp(context: Context): String? = runCatching {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, 0)
    }
    "${info.longVersionCode}:${info.lastUpdateTime}"
}.getOrNull()

actual val commonPlatformModule: Module = module {
    single<KeyValueStoreFactory> {
        val context = get<Context>()
        KeyValueStoreFactory { name ->
            SharedPreferencesStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))
        }
    }
    single<InstallStamp> {
        val context = get<Context>()
        InstallStamp { installStamp(context) }
    }
}
