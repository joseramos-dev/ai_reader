package dev.joseramos.aireader.pdf

import org.koin.core.module.Module
import org.koin.dsl.module

/** Lo que aporta cada plataforma: las fábricas de [PdfTextDocumentFactory] y [PdfRendererFactory]. */
expect val pdfPlatformModule: Module

val pdfModule = module {
    includes(pdfPlatformModule)
}
