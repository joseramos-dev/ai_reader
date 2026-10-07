plugins {
    alias(libs.plugins.aireader.kmp.feature)
}

kotlin {
    android {
        namespace = "dev.joseramos.aireader.feature.characters"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":ai:characters"))
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit)
        }
    }
}

compose.resources {
    packageOfResClass = "dev.joseramos.aireader.feature.characters.generated.resources"
    publicResClass = false
}
