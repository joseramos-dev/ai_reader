plugins {
    alias(libs.plugins.aireader.kmp.library)
    alias(libs.plugins.aireader.kmp.room)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "dev.joseramos.aireader.core.data"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.kotlinx.serialization.json)
        }
        getByName("androidMain").dependencies {
            implementation(libs.tink.android)
        }
        getByName("desktopMain").dependencies {
            implementation(libs.jna.platform)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit)
            implementation(libs.androidx.room.testing)
        }
    }
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
}
