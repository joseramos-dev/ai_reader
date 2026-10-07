plugins {
    alias(libs.plugins.aireader.kmp.library)
}

kotlin {
    android {
        namespace = "dev.joseramos.aireader.ai.models"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:data"))
            implementation(libs.okhttp)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit)
            implementation(libs.okhttp.mockwebserver)
        }
    }
}
