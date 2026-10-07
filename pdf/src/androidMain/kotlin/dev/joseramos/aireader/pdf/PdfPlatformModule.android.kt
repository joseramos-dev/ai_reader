package dev.joseramos.aireader.pdf

import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

actual val pdfPlatformModule: Module = module {
    single { AndroidPdfTextDocumentFactory(get()) } bind PdfTextDocumentFactory::class
    single { AndroidPdfRendererFactory() } bind PdfRendererFactory::class
}
