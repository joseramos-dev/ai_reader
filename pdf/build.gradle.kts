plugins {
    alias(libs.plugins.aireader.kmp.library)
    alias(libs.plugins.aireader.kmp.compose)
}

// Render de páginas y extracción de texto e índice. En Android, el `PdfRenderer` del sistema y PdfBox-Android;
// en escritorio, Apache PDFBox. Las líneas con su geometría se devuelven como TextLine de :text.
kotlin {
    android {
        namespace = "dev.joseramos.aireader.pdf"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":text"))
        }
        getByName("androidMain").dependencies {
            implementation(libs.pdfbox.android)
        }
        getByName("desktopMain").dependencies {
            implementation(project(":core:common"))
            implementation(libs.pdfbox)
        }
        getByName("desktopTest").dependencies {
            // Skia (la librería nativa de Compose) para convertir las páginas renderizadas en ImageBitmap.
            implementation(compose.desktop.currentOs)
            implementation(libs.junit)
        }
    }
}
