plugins {
    alias(libs.plugins.aireader.kmp.feature)
}

kotlin {
    android {
        namespace = "dev.joseramos.aireader.feature.reader"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":pdf"))
            implementation(project(":text"))
            implementation(project(":tts"))
            implementation(project(":ai:llm"))
            implementation(project(":ai:characters"))
            implementation(project(":feature:characters"))
            implementation(libs.coil.compose)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit)
        }
    }
}

compose.resources {
    packageOfResClass = "dev.joseramos.aireader.feature.reader.generated.resources"
    publicResClass = false
}
