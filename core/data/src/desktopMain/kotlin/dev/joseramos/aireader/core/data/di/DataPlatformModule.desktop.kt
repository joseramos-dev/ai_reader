package dev.joseramos.aireader.core.data.di

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.sun.jna.platform.win32.Crypt32Util
import dev.joseramos.aireader.core.common.AppDirs
import dev.joseramos.aireader.core.common.IoDispatcher
import dev.joseramos.aireader.core.data.db.AppDatabase
import dev.joseramos.aireader.core.data.db.MIGRATION_6_7
import dev.joseramos.aireader.core.data.settings.SecretCipher
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.module.Module
import org.koin.dsl.module

/** Cifra con la protección de datos de Windows (DPAPI): solo el mismo usuario de Windows puede descifrarlo. */
private class DpapiSecretCipher : SecretCipher {
    override fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray =
        Crypt32Util.cryptProtectData(plain, associatedData, 0, "AI Reader", null)

    override fun decrypt(encrypted: ByteArray, associatedData: ByteArray): ByteArray =
        Crypt32Util.cryptUnprotectData(encrypted, associatedData, 0, null)
}

actual val dataPlatformModule: Module = module {
    single {
        val file = File(get<AppDirs>().files, AppDatabase.NAME).apply { parentFile?.mkdirs() }
        Room.databaseBuilder<AppDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(get<CoroutineDispatcher>(IoDispatcher))
            .addMigrations(MIGRATION_6_7)
            .build()
    }
    single<SecretCipher> { DpapiSecretCipher() }
}
