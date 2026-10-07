package dev.joseramos.aireader.pdf

import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

actual val pdfPlatformModule: Module = module {
    single { PdfBoxTextDocumentFactory(get()) } bind PdfTextDocumentFactory::class
    single { PdfBoxRendererFactory(get()) } bind PdfRendererFactory::class
}
