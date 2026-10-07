package dev.joseramos.aireader.ai.llm

import org.koin.core.module.Module
import org.koin.dsl.module

actual val llmPlatformModule: Module = module {
    factory<BudgetNotifier> { NoBudgetNotifier }
}
