package dev.joseramos.aireader.feature.characters

import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val charactersFeatureModule = module {
    viewModelOf(::CharactersViewModel)
    viewModelOf(::RelationsGraphViewModel)
}
