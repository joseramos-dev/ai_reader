plugins {
    alias(libs.plugins.aireader.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "dev.joseramos.aireader.text"
    }

    sourceSets {
        // Las líneas con su geometría (TextLine) se guardan como JSON.
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit)
        }
    }
}
