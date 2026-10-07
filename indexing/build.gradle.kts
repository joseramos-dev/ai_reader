plugins {
    alias(libs.plugins.aireader.kmp.library)
}

// Importación de libros e indexación en segundo plano (texto, capítulos y embeddings).
kotlin {
    android {
        namespace = "dev.joseramos.aireader.indexing"
        // Textos de la notificación de indexación.
        androidResources { enable = true }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:data"))
            implementation(project(":pdf"))
            implementation(project(":text"))
            implementation(libs.kotlinx.serialization.json)
        }
        getByName("androidMain").dependencies {
            implementation(libs.koin.androidx.workmanager)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.work.runtime.ktx)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.androidx.room.runtime)
        }
    }
}
