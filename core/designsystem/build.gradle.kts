plugins {
    alias(libs.plugins.aireader.kmp.library)
    alias(libs.plugins.aireader.kmp.compose)
}

// Sistema de diseño compartido: tema, componentes y recursos (fuentes y textos) de Compose Multiplatform.
kotlin {
    android {
        namespace = "dev.joseramos.aireader.core.designsystem"
        androidResources { enable = true }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            api(compose.materialIconsExtended)
            api(libs.haze)
            api(libs.haze.blur)
        }
        getByName("androidMain").dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }
    }
}

compose.resources {
    packageOfResClass = "dev.joseramos.aireader.core.designsystem.generated.resources"
    publicResClass = false
}
