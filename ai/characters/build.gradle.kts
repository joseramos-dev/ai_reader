plugins {
    alias(libs.plugins.aireader.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

// Personajes de las novelas: extracción progresiva con IA, fusión de apodos y reglas sin spoilers.
kotlin {
    android {
        namespace = "dev.joseramos.aireader.ai.characters"
        // Textos de la notificación del análisis.
        androidResources { enable = true }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:data"))
            implementation(project(":indexing"))
            implementation(project(":text"))
            implementation(project(":ai:llm"))
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
        }
    }
}
