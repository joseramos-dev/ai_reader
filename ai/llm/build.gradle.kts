plugins {
    alias(libs.plugins.aireader.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

// Cliente de la API de Gemini (REST con OkHttp), prompts, hechos clave y repasos.
kotlin {
    android {
        namespace = "dev.joseramos.aireader.ai.llm"
        // Textos de la notificación de presupuesto.
        androidResources { enable = true }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:data"))
            implementation(project(":indexing"))
            implementation(project(":text"))
            implementation(libs.okhttp)
            implementation(libs.kotlinx.serialization.json)
        }
        getByName("androidMain").dependencies {
            implementation(libs.androidx.core.ktx)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.datastore.preferences)
        }
    }
}
