package dev.joseramos.aireader.core.data.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.joseramos.aireader.core.common.AppDirs
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
import dev.joseramos.aireader.core.data.settings.SecretStore
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import dev.joseramos.aireader.core.data.settings.UsageRepository
import java.io.File
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

private val settingsStore = named(SettingsRepository.SETTINGS_STORE)
private val secretsStore = named(SecretStore.SECRETS_STORE)

/**
 * Lo que cada plataforma aporta a los datos: la base de datos [AppDatabase] (su fichero y su driver) y el
 * [dev.joseramos.aireader.core.data.settings.SecretCipher]. Necesita un [AppDirs] ya registrado.
 */
expect val dataPlatformModule: Module

/** Mismo fichero que usaba `preferencesDataStoreFile` en Android: los ajustes de quien actualiza se conservan. */
private fun preferencesStore(dirs: AppDirs, name: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.create { File(dirs.files, "datastore/$name.preferences_pb") }

val dataModule = module {
    includes(dataPlatformModule)

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

    single(settingsStore) { preferencesStore(get(), "settings") }
    single(secretsStore) { preferencesStore(get(), "secrets") }

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
