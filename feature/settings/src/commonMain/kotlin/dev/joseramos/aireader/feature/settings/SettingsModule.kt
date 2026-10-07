package dev.joseramos.aireader.feature.settings

import dev.joseramos.aireader.core.common.IoDispatcher
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val settingsModule = module {
    viewModel { SettingsViewModel(get(), get(IoDispatcher), get(), get(), get(), get()) }
}
