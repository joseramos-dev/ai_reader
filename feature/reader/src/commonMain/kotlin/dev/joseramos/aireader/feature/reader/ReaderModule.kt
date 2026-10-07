package dev.joseramos.aireader.feature.reader

import dev.joseramos.aireader.core.common.ApplicationScope
import dev.joseramos.aireader.core.common.IoDispatcher
import dev.joseramos.aireader.feature.reader.player.PlayerViewModel
import dev.joseramos.aireader.feature.reader.summary.KeyPointsViewModel
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val readerModule = module {
    factoryOf(::SourcePassageLocator)
    viewModel {
        ReaderViewModel(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(IoDispatcher),
            get(ApplicationScope),
            get()
        )
    }
    viewModelOf(::KeyPointsViewModel)
    viewModelOf(::PlayerViewModel)
}
