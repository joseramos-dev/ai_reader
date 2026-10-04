package dev.joseramos.aireader.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.joseramos.aireader.core.data.db.AppDatabase
import dev.joseramos.aireader.core.data.db.MIGRATION_6_7
import dev.joseramos.aireader.core.data.settings.SecretStore
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(MIGRATION_6_7)
            .build()

    @Provides fun bookDao(db: AppDatabase) = db.bookDao()

    @Provides fun chapterDao(db: AppDatabase) = db.chapterDao()

    @Provides fun pageTextDao(db: AppDatabase) = db.pageTextDao()

    @Provides fun pageLayoutDao(db: AppDatabase) = db.pageLayoutDao()

    @Provides fun chunkDao(db: AppDatabase) = db.chunkDao()

    @Provides fun chunkEmbeddingDao(db: AppDatabase) = db.chunkEmbeddingDao()

    @Provides fun summaryDao(db: AppDatabase) = db.summaryDao()

    @Provides fun readingPositionDao(db: AppDatabase) = db.readingPositionDao()

    @Provides fun chatDao(db: AppDatabase) = db.chatDao()

    @Provides fun downloadedModelDao(db: AppDatabase) = db.downloadedModelDao()

    @Provides fun bookmarkDao(db: AppDatabase) = db.bookmarkDao()

    @Provides fun characterDao(db: AppDatabase) = db.characterDao()

    @Provides fun highlightDao(db: AppDatabase) = db.highlightDao()

    @Provides
    @Singleton
    @Named(SettingsRepository.SETTINGS_STORE)
    fun provideSettingsStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides
    @Singleton
    @Named(SecretStore.SECRETS_STORE)
    fun provideSecretsStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("secrets") }

    /** AEAD de Tink cuyo keyset está cifrado con una clave maestra del Android Keystore. */
    @Provides
    @Singleton
    fun provideAead(@ApplicationContext context: Context): Aead {
        AeadConfig.register()
        return AndroidKeysetManager.Builder()
            .withSharedPref(context, "aireader_keyset", "aireader_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://aireader_master_key")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }
}
