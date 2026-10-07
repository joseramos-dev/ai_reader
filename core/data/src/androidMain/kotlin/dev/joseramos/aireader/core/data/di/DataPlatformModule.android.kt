package dev.joseramos.aireader.core.data.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dev.joseramos.aireader.core.common.IoDispatcher
import dev.joseramos.aireader.core.data.db.AppDatabase
import dev.joseramos.aireader.core.data.db.MIGRATION_6_7
import dev.joseramos.aireader.core.data.settings.SecretCipher
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.module.Module
import org.koin.dsl.module

/** AEAD de Tink cuyo keyset está cifrado con una clave maestra del Android Keystore. */
private fun createAead(context: Context): Aead {
    AeadConfig.register()
    return AndroidKeysetManager.Builder()
        .withSharedPref(context, "aireader_keyset", "aireader_keyset_prefs")
        .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
        .withMasterKeyUri("android-keystore://aireader_master_key")
        .build()
        .keysetHandle
        .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
}

/** Cifra con AES-256-GCM (Tink); la clave de cifrado vive en el Android Keystore y nunca sale de él. */
private class AeadSecretCipher(private val aead: Aead) : SecretCipher {
    override fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray = aead.encrypt(plain, associatedData)

    override fun decrypt(encrypted: ByteArray, associatedData: ByteArray): ByteArray =
        aead.decrypt(encrypted, associatedData)
}

actual val dataPlatformModule: Module = module {
    single {
        Room.databaseBuilder<AppDatabase>(get<Context>(), AppDatabase.NAME)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(get<CoroutineDispatcher>(IoDispatcher))
            .addMigrations(MIGRATION_6_7)
            .build()
    }
    single<SecretCipher> { AeadSecretCipher(createAead(get())) }
}
