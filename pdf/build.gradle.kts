plugins {
    alias(libs.plugins.aireader.android.library)
}

android {
    namespace = "dev.joseramos.aireader.pdf"
}

// Render de páginas (PdfRenderer del sistema) y extracción de texto e índice (PdfBox-Android).
dependencies {
    implementation(libs.pdfbox.android)
}
