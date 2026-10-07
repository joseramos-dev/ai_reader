package dev.joseramos.aireader

import android.content.pm.ApplicationInfo
import dev.joseramos.aireader.core.common.AppDirs
import dev.joseramos.aireader.core.common.AppInfo
import dev.joseramos.aireader.shared.sharedModules
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** Lo que solo existe en Android: las carpetas de la app y sus datos de versión. */
private val androidModule = module {
    single { AppDirs(files = androidContext().filesDir, cache = androidContext().cacheDir) }
    single {
        val context = androidContext()
        AppInfo(
            version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty(),
            debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        )
    }
}

/** Todos los módulos de Koin de la app Android. */
val appModules = listOf(androidModule) + sharedModules
