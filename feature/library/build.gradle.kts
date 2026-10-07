plugins {
    alias(libs.plugins.aireader.kmp.feature)
}

kotlin {
    android {
        namespace = "dev.joseramos.aireader.feature.library"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":indexing"))
            implementation(libs.coil.compose)
        }
    }
}

compose.resources {
    packageOfResClass = "dev.joseramos.aireader.feature.library.generated.resources"
    publicResClass = false
}
