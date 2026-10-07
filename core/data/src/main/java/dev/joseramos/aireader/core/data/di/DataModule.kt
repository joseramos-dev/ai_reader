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
import dev.joseramos.aireader.core.common.IoDispatcher
import dev.joseramos.aireader.core.data.book.BookContentRepository
import dev.joseramos.aireader.core.data.book.BookRepository
import dev.joseramos.aireader.core.data.book.BookmarkRepository
import dev.joseramos.aireader.core.data.book.CharacterRepository
import dev.joseramos.aireader.core.data.book.ChatRepository
import dev.joseramos.aireader.core.data.book.HighlightRepository
import dev.joseramos.aireader.core.data.book.KeyPointsRepository
import dev.joseramos.aireader.core.data.book.ReadingPositionRepository
import dev.joseramos.aireader.core.data.db.AppDatabase
import dev.joseramos.aireader.core.data.db.MIGRATION_6_7
import dev.joseramos.aireader.core.data.settings.SecretStore
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import dev.joseramos.aireader.core.data.settings.UsageRepository
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Aead de Tink cuyo keyset está cifrado con una clave maestra del Android Keystore. */
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

private val settingsStore = named(SettingsRepository.SETTINGS_STORE)
private val secretsStore = named(SecretStore.SECRETS_STORE)

val dataModule = module {
    single {
        Room.databaseBuilder(get<Context>(), AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(MIGRATION_6_7)
            .build()
    }
    factory { get<AppDatabase>().bookDao() }
    factory { get<AppDatabase>().chapterDao() }
    factory { get<AppDatabase>().pageTextDao() }
    factory { get<AppDatabase>().pageLayoutDao() }
    factory { get<AppDatabase>().chunkDao() }
    factory { get<AppDatabase>().chunkEmbeddingDao() }
    factory { get<AppDatabase>().keyPointsDao() }
    factory { get<AppDatabase>().readingPositionDao() }
    factory { get<AppDatabase>().chatDao() }
    factory { get<AppDatabase>().downloadedModelDao() }
    factory { get<AppDatabase>().bookmarkDao() }
    factory { get<AppDatabase>().characterDao() }
    factory { get<AppDatabase>().highlightDao() }

    single<DataStore<Preferences>>(settingsStore) {
        val context = get<Context>()
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }
    }
    single<DataStore<Preferences>>(secretsStore) {
        val context = get<Context>()
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("secrets") }
    }
    single<Aead> { createAead(get()) }

    single { SettingsRepository(get(settingsStore)) }
    single { UsageRepository(get(settingsStore)) }
    single { SecretStore(get(secretsStore), get()) }

    single { BookRepository(get(), get(IoDispatcher)) }
    single { BookContentRepository(get(), get(), get()) }
    single { BookmarkRepository(get()) }
    single { CharacterRepository(get()) }
    single { ChatRepository(get()) }
    single { HighlightRepository(get()) }
    single { KeyPointsRepository(get()) }
    single { ReadingPositionRepository(get()) }
}
