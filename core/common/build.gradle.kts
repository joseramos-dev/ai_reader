plugins {
    alias(libs.plugins.aireader.kmp.library)
}

kotlin {
    android {
        namespace = "dev.joseramos.aireader.core.common"
    }

    sourceSets {
        getByName("androidMain").dependencies {
            implementation(libs.androidx.core.ktx)
        }
    }
}
