plugins {
    alias(libs.plugins.aireader.kmp.feature)
}

kotlin {
    android {
        namespace = "dev.joseramos.aireader.feature.settings"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":ai:models"))
            implementation(project(":tts"))
            implementation(project(":text"))
            implementation(project(":ai:rag"))
        }
    }
}

compose.resources {
    packageOfResClass = "dev.joseramos.aireader.feature.settings.generated.resources"
    publicResClass = false
}
